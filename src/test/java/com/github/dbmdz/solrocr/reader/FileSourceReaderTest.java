package com.github.dbmdz.solrocr.reader;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.dbmdz.solrocr.model.SourcePointer;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FileSourceReaderTest {

  private final Path filePath = Paths.get("src/test/resources/data/hocr.html");
  private final SourcePointer pointer = SourcePointer.parse(filePath.toString());
  private final int maxCacheEntries = 10;

  @Test
  void shouldCacheSectionsProperly() throws IOException {
    FileSourceReader reader = new FileSourceReader(filePath, pointer, 8192, 3);
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
    SourceReader reader = new FileSourceReader(filePath, pointer, 8192, maxCacheEntries);
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
    SourceReader reader = new FileSourceReader(filePath, pointer, sectionSize, 3);
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
    SourceReader reader = new FileSourceReader(filePath, pointer, sectionSize, maxCacheEntries);
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
}
