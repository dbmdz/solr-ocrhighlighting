package com.github.dbmdz.solrocr.reader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertThrows;

import com.github.dbmdz.solrocr.model.SourcePointer;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.UploadObjectArgs;
import io.minio.errors.*;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
public class S3ObjectSourceReaderTest {
  @Container static final MinIOContainer container = new MinIOContainer("minio/minio");
  static MinioClient s3Client;
  int maxCacheEntries = 10;
  static final String s3Source = "s3://my-test-bucket/hocr.html";
  static final Path filePath = Paths.get("src/test/resources/data/hocr.html");

  @BeforeAll
  public static void setUp()
      throws ServerException, InsufficientDataException, ErrorResponseException, IOException,
          NoSuchAlgorithmException, InvalidKeyException, InvalidResponseException,
          XmlParserException, InternalException {
    s3Client =
        MinioClient.builder()
            .endpoint(container.getS3URL())
            .credentials(container.getUserName(), container.getPassword())
            .build();

    s3Client.makeBucket(MakeBucketArgs.builder().bucket("my-test-bucket").build());
    s3Client.uploadObject(
        UploadObjectArgs.builder()
            .bucket("my-test-bucket")
            .object("hocr.html")
            .filename(filePath.toString())
            .build());
  }

  @Test
  void shouldCacheSectionsProperly() throws IOException {
    S3ObjectSourceReader reader =
        new S3ObjectSourceReader(
            s3Client, URI.create(s3Source), SourcePointer.parse(s3Source), 8192, 3);
    reader.getAsciiSection(128);
    assertThat(reader.cachedSectionIdxes).containsExactlyInAnyOrder(-1, -1, 0);
    assertThat(reader.cache[0].section.start).isEqualTo(0);
    assertThat(reader.cache[0].section.end).isEqualTo(8192);
    reader.getAsciiSection(8192 + 128);
    assertThat(reader.cachedSectionIdxes).containsExactlyInAnyOrder(-1, 1, 0);
    assertThat(reader.cache[1]).isNotNull();
    reader.getAsciiSection(2 * 8192 + 128);
    assertThat(reader.cachedSectionIdxes).containsExactlyInAnyOrder(2, 1, 0);
    assertThat(reader.cache[2]).isNotNull();
    // Test cache eviction
    reader.getAsciiSection(3 * 8192 + 128);
    assertThat(reader.cachedSectionIdxes).containsExactlyInAnyOrder(2, 1, 3);
    assertThat(reader.cache[0]).isNull();
    reader.getAsciiSection(16 * 8192 + 128);
    assertThat(reader.cachedSectionIdxes).containsExactlyInAnyOrder(2, 16, 3);
    assertThat(reader.cache[1]).isNull();
  }

  @Test
  void shouldReadUtf8StringCorrectly() throws IOException {
    SourceReader reader =
        new S3ObjectSourceReader(
            s3Client, URI.create(s3Source), SourcePointer.parse(s3Source), 1024, maxCacheEntries);
    // No UTF8 misalignment offset
    assertThat(reader.readUtf8String(422871, 97))
        .isEqualTo(
            "<span class='ocrx_word' id='word_22_188' title='bbox 736 1671 865 1713; x_wconf 96'>dünne</span>");
    // Misaligned start offset
    assertThat(reader.readUtf8String(422957, 4)).isEqualTo("nne");
    // Misaligned end offset
    assertThat(reader.readUtf8String(422871, 86))
        .isEqualTo(
            "<span class='ocrx_word' id='word_22_188' title='bbox 736 1671 865 1713; x_wconf 96'>d");
  }

  @ParameterizedTest
  @ValueSource(
      ints = {64, 128, 256, 512, 1024, 2048, 4096, 8192, 16_384, 32_768, 65_536, 131_072, 262_144})
  void shouldReadAsciiStringCorrectlyWithDifferentSectionSizes(int sectionSize) throws IOException {
    // Reduce number of cache entries to force some cache evictions
    SourceReader reader =
        new S3ObjectSourceReader(
            s3Client, URI.create(s3Source), SourcePointer.parse(s3Source), sectionSize, 3);
    // Choose offsets to force reading across multiple sections
    int startOffset = 2_715_113 - (maxCacheEntries / 2 * sectionSize) - (sectionSize / 2);
    int endOffset = startOffset + (maxCacheEntries / 2 * sectionSize);
    int readLen = endOffset - startOffset;
    byte[] expectedData = new byte[readLen];
    try (ByteChannel chan = Files.newByteChannel(filePath).position(startOffset)) {
      chan.read(ByteBuffer.wrap(expectedData));
    }
    String expectedStr = new String(expectedData, 0, 0, expectedData.length);
    assertThat(reader.readAsciiString(startOffset, readLen)).isEqualTo(expectedStr);
  }

  @ParameterizedTest
  @ValueSource(
      ints = {64, 128, 256, 512, 1024, 2048, 4096, 8192, 16_384, 32_768, 65_536, 131_072, 262_144})
  public void shouldReadCorrectlyAlignedSections(int sectionSize) throws IOException {
    SourceReader reader =
        new S3ObjectSourceReader(
            s3Client,
            URI.create(s3Source),
            SourcePointer.parse(s3Source),
            sectionSize,
            maxCacheEntries);
    int offset = (sectionSize * 4) + (sectionSize / 2);
    SourceReader.Section section = reader.getAsciiSection(offset);
    byte[] expectedData = new byte[sectionSize];
    try (ByteChannel chan = Files.newByteChannel(filePath).position(sectionSize * 4L)) {
      chan.read(ByteBuffer.wrap(expectedData));
    }
    String expectedStr = new String(expectedData, 0, 0, expectedData.length);
    assertThat(section.start).isEqualTo(sectionSize * 4);
    assertThat(section.end).isEqualTo(sectionSize * 5);
    assertThat(section.text).isEqualTo(expectedStr);
  }

  @Test
  public void testMissingS3ClientThrowsException() {
    assertThrows(
        RuntimeException.class,
        () ->
            new S3ObjectSourceReader(
                null, URI.create(s3Source), SourcePointer.parse(s3Source), 1024, maxCacheEntries));
  }

  @Test
  public void testMissingBucketThrowsException() {
    String pointerStr = "s3://unknown-bucket/hocr.html";
    assertThrows(
        RuntimeException.class,
        () ->
            new FileSourceReader(
                Paths.get(pointerStr), SourcePointer.parse(pointerStr), 1024, maxCacheEntries));
  }
}
