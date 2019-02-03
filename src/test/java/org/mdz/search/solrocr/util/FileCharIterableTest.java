package org.mdz.search.solrocr.util;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import org.assertj.core.internal.bytebuddy.utility.RandomString;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FileCharIterableTest {
  String testText;
  Path testFile;

  @BeforeEach
  void setUp() throws IOException {
    this.testText = RandomString.make(2^16);
    this.testFile = Files.createTempFile("solrocrtest", ".txt");
    Files.write(this.testFile, this.testText.getBytes(StandardCharsets.UTF_16BE));
  }

  @AfterEach
  void tearDown() throws IOException {
    Files.delete(this.testFile);
  }

  @Test
  void testReadSeek() throws IOException, URISyntaxException {
    assertThat(testText.length()).isEqualTo(Files.size(testFile) / 2);
    Random rand = new Random();
    RandomAccessFile fp = new RandomAccessFile(testFile.toFile(), "r");

    for (int i=0; i < 100000; i++) {
      int idx = rand.nextInt(testText.length() - 8);
      byte[] u16Bytes = new byte[2];
      fp.seek(idx * 2);
      fp.read(u16Bytes);
      char fromString = testText.charAt(idx);
      char decoded = new String(u16Bytes, StandardCharsets.UTF_16BE).charAt(0);
      assertThat(decoded).isEqualTo(fromString);
    }
  }

}