package org.mdz.search.solrocr.util;

import com.google.common.math.IntMath;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.CharacterIterator;
import java.util.Random;
import org.assertj.core.internal.bytebuddy.utility.RandomString;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FileCharIteratorTest {
  private final static int TEXT_SIZE = IntMath.pow(2, 18); // 256k chars, i.e. a 512KiB file
  private String testText;
  private Path testFile;
  private FileCharIterator it;

  @BeforeEach
  void setUp() throws IOException {
    this.testText = RandomString.make(TEXT_SIZE);
    this.testFile = Files.createTempFile("solrocrtest", ".txt");
    Files.write(this.testFile, this.testText.getBytes(StandardCharsets.UTF_16));
    this.it = new FileCharIterator(testFile);
  }

  @AfterEach
  void tearDown() throws IOException {
    Files.delete(this.testFile);
  }

  @Test
  void testInit() {
    assertThat(it.length()).isEqualTo(TEXT_SIZE);
  }

  @Test
  void testSeek() {
    Random rand = new Random();
    for (int i = 0; i < TEXT_SIZE / 4; i++) {
      int idx = rand.nextInt(testText.length() - 6);
      char decoded = it.charAt(idx);
      char fromString = testText.charAt(idx);
      assertThat(decoded).isEqualTo(fromString);
    }
  }

  @Test
  void testSubSequence() {
    String strSeq = testText.substring(10, 100);
    String itSeq = it.subSequence(10, 100).toString();
    assertThat(itSeq).isEqualTo(strSeq);
  }

  @Test
  void testSeekEnd() throws IOException {
    it.setIndex(it.getEndIndex());
    assertThat(it.current()).isEqualTo(CharacterIterator.DONE);
  }
}