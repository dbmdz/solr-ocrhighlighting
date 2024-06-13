package com.github.dbmdz.solrocr.reader;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.dbmdz.solrocr.model.SourcePointer;
import com.github.dbmdz.solrocr.reader.SourceReader.Section;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.commons.io.IOUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MultiFileSourceReaderTest {
  private final List<Path> filePaths;
  private final SourcePointer pointer;
  private final int maxCacheEntries = 10;

  MultiFileSourceReaderTest() throws IOException {
    Path root = Paths.get("src/test/resources/data/alto_multi");
    filePaths = new ArrayList<>();
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(root, "1860-11-30*.xml")) {
      stream.forEach(filePaths::add);
    }
    filePaths.sort(Comparator.comparing(Path::toString));
    pointer =
        SourcePointer.parse(
            filePaths.stream()
                .map(p -> p.toAbsolutePath().toString())
                .collect(Collectors.joining("+")));
  }

  private byte[] readData(int startOffset, int endOffset) throws IOException {
    if (startOffset >= endOffset) {
      throw new IllegalArgumentException("startOffset must be less than endOffset");
    }

    byte[] data = new byte[endOffset - startOffset];
    int outOffset = 0;
    long offset = 0; // Use long to avoid overflow

    for (Path filePath : filePaths) {
      long fSize = Files.size(filePath);

      if (offset + fSize <= startOffset) {
        offset += fSize;
        continue;
      }

      try (SeekableByteChannel chan = Files.newByteChannel(filePath)) {
        long position = Math.max(0, startOffset - offset);
        chan.position(position);
        int bytesToRead = (int) Math.min(data.length - outOffset, fSize - position);

        ByteBuffer buffer = ByteBuffer.wrap(data, outOffset, bytesToRead);
        int bytesRead = 0;
        while (buffer.hasRemaining()) {
          int read = chan.read(buffer);
          if (read == -1) {
            break;
          }
          bytesRead += read;
        }
        outOffset += bytesRead;
        offset += position + bytesRead;

        if (offset >= endOffset) {
          break;
        }
      }
    }

    if (outOffset < data.length) {
      throw new IOException("Not enough data to read");
    }

    return data;
  }

  @ParameterizedTest
  @ValueSource(
      ints = {64, 128, 256, 512, 1024, 2048, 4096, 8192, 16_384, 32_768, 65_536, 131_072, 262_144})
  void shouldReadAsciiStringCorrectlyWithDifferentSectionSizes(int sectionSize) throws IOException {
    // Reduce number of cache entries to force some cache evictions
    SourceReader reader = new MultiFileSourceReader(filePaths, pointer, sectionSize, 3);
    // Choose offsets to force reading across multiple sections
    int startOffset =
        Math.max(
            0, (reader.length() / 2) - (maxCacheEntries / 2 * sectionSize) - (sectionSize / 2));
    int endOffset = Math.min(startOffset + (maxCacheEntries / 2 * sectionSize), reader.length());
    int readLen = endOffset - startOffset;
    byte[] expectedData = readData(startOffset, endOffset);
    String expectedStr = new String(expectedData, 0, 0, expectedData.length);
    String actualStr = reader.readAsciiString(startOffset, readLen);
    assertThat(actualStr).isEqualTo(expectedStr);
  }

  @ParameterizedTest
  @ValueSource(
      ints = {64, 128, 256, 512, 1024, 2048, 4096, 8192, 16_384, 32_768, 65_536, 131_072, 262_144})
  public void shouldReadCorrectlyAlignedSections(int sectionSize) throws IOException {
    SourceReader reader =
        new MultiFileSourceReader(filePaths, pointer, sectionSize, maxCacheEntries);
    // FIXME: Pick offset that falls on a file boundary!
    int offset = (int) (Files.size(filePaths.get(2)) - 32);
    Section section = reader.getAsciiSection(offset);
    int sectionStart = (offset / sectionSize) * sectionSize;
    byte[] expectedData = readData(sectionStart, sectionStart + sectionSize);
    String expectedStr = new String(expectedData, 0, 0, expectedData.length);
    assertThat(section.start).isEqualTo(sectionStart);
    assertThat(section.end).isEqualTo(sectionStart + sectionSize);
    assertThat(section.text).isEqualTo(expectedStr);
  }

  @Test
  public void shouldReturnValidReader() throws IOException {
    SourceReader reader =
        new MultiFileSourceReader(filePaths, pointer, 512 * 1024, maxCacheEntries);
    String fromReader =
        IOUtils.toString(
            Channels.newReader(reader.getByteChannel(), StandardCharsets.UTF_8.name()));
    String fromFiles =
        filePaths.stream()
            .map(
                fp -> {
                  try {
                    return new String(Files.readAllBytes(fp), StandardCharsets.UTF_8);
                  } catch (IOException e) {
                    throw new RuntimeException(e);
                  }
                })
            .collect(Collectors.joining(""));
    assertThat(fromReader).isEqualTo(fromFiles);
  }
}
