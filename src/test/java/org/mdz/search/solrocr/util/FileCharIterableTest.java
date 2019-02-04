package org.mdz.search.solrocr.util;

import java.io.IOException;
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
    Files.write(this.testFile, this.testText.getBytes(StandardCharsets.UTF_16));
  }

  @AfterEach
  void tearDown() throws IOException {
    Files.delete(this.testFile);
  }

  @Test
  void testInit() throws IOException {
    FileCharIterable it = new FileCharIterable(testFile);
    assertThat(it.length()).isEqualTo(2^16);
  }

  @Test
  void testSeek() throws IOException, URISyntaxException {
    FileCharIterable it = new FileCharIterable(testFile);
    Random rand = new Random();
    for (int i=0; i < 100000; i++) {
      int idx = rand.nextInt(testText.length() - 6);
      char decoded = it.charAt(idx);
      char fromString = testText.charAt(idx);
      assertThat(decoded).isEqualTo(fromString);
    }
  }

}