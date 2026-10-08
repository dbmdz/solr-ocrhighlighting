package com.github.dbmdz.solrocr.lucene.filters;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ByteSeekableReaderTest {
  @TempDir Path tempDir;

  @ParameterizedTest
  @CsvSource({"ABCDEFG,true", "AéBCDEF,false", "A€BCDEF,false", "😀ABCDEF,false"})
  void mixedReadSizesAccountForAsciiBmpAndSurrogateLeftovers(String text, boolean asciiOnly)
      throws IOException {
    Path path = tempDir.resolve("mixed-reads.txt");
    Files.write(path, text.getBytes(StandardCharsets.UTF_8));
    try (ByteSeekableReader reader = new ByteSeekableReader(Files.newByteChannel(path))) {
      char[] buffer = new char[5];
      // A one-character request decodes two UTF-16 characters, leaving the second buffered.
      assertThat(reader.read(buffer, 1, 1)).isEqualTo(1);
      assertThat(new String(buffer, 1, 1)).isEqualTo(text.substring(0, 1));
      assertThat(reader.read(buffer, 1, 3)).isEqualTo(3);
      assertThat(new String(buffer, 1, 3)).isEqualTo(text.substring(1, 4));
      assertThat(reader.asciiOnly()).isEqualTo(asciiOnly);
      assertThat(reader.replacementBytes()).isZero();

      char[] tail = new char[text.length() - 4];
      assertThat(reader.read(tail, 0, tail.length)).isEqualTo(tail.length);
      assertThat(new String(tail)).isEqualTo(text.substring(4));
      assertThat(reader.read(buffer, 1, 3)).isEqualTo(-1);
      assertThat(reader.read(buffer, 1, 3)).isEqualTo(-1);
    }
  }

  @Test
  void leftoverReplacementCanRepresentOneSourceByte() throws IOException {
    Path path = tempDir.resolve("truncated.txt");
    Files.write(path, "Aé".getBytes(StandardCharsets.UTF_8));
    try (ByteSeekableReader reader = new ByteSeekableReader(Files.newByteChannel(path))) {
      reader.position(0, 2); // A followed by only the first byte of é.
      char[] buffer = new char[3];
      assertThat(reader.read(buffer, 0, 1)).isEqualTo(1);
      assertThat(buffer[0]).isEqualTo('A');
      assertThat(reader.replacementBytes()).isZero();
      assertThat(reader.read(buffer, 0, 3)).isEqualTo(1);
      assertThat(buffer[0]).isEqualTo('\uFFFD');
      assertThat(reader.asciiOnly()).isFalse();
      assertThat(reader.replacementBytes()).isEqualTo(1);
      assertThat(reader.read(buffer, 0, 3)).isEqualTo(-1);
    }
  }
}
