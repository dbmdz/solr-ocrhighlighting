package org.mdz.search.solrocr.util;

import com.google.common.math.IntMath;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.CharacterIterator;
import java.util.Random;
import org.apache.commons.text.RandomStringGenerator;
import org.assertj.core.internal.bytebuddy.utility.RandomString;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class FileCharIteratorTest {
  private final static int TEXT_SIZE = IntMath.pow(2, 18); // 256k chars, i.e. a 512KiB file
  private String testText;
  private Path testFile;
  private FileCharIterator it;

  void prepareFixtures(String encoding) throws IOException {
    this.prepareFixtures(encoding, false);
  }

  void prepareFixtures(String encoding, boolean asciiOnly) throws IOException {
    if (asciiOnly) {
      this.testText = RandomString.make(TEXT_SIZE);
    } else {
      RandomStringGenerator stringGen = new RandomStringGenerator.Builder()
          .build();
      // Add ASCII prefix to not throw off endianness heuristic
      this.testText = "A" + stringGen.generate(TEXT_SIZE);
    }
    this.testFile = Files.createTempFile("solrocrtest", ".txt");
    Files.write(this.testFile, this.testText.getBytes(encoding));
    this.it = new FileCharIterator(testFile);
  }

  @AfterEach
  void tearDown() throws IOException {
    Files.delete(this.testFile);
  }

  @ParameterizedTest
  @ValueSource(strings = {"UTF_16", "UTF_16BE", "UTF_16LE"})
  void testInit(String encoding) throws IOException {
    this.prepareFixtures(encoding);
    assertThat(it.length()).isEqualTo(testText.length());
  }

  @ParameterizedTest
  @ValueSource(strings = {"UTF_16", "UTF_16BE", "UTF_16LE"})
  void testSeek(String encoding) throws IOException {
    this.prepareFixtures(encoding);
    Random rand = new Random();
    for (int i = 0; i < TEXT_SIZE / 4; i++) {
      int idx = rand.nextInt(testText.length() - 6);
      char decoded = it.charAt(idx);
      char fromString = testText.charAt(idx);
      assertThat(decoded).isEqualTo(fromString);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"UTF_16", "UTF_16BE", "UTF_16LE"})
  void testSubSequence(String encoding) throws IOException {
    // substring/subSequence is broken in Java if the String contains
    // surrogate pairs, so we restrict ourselves to ascii here
    this.prepareFixtures(encoding, true);
    String strSeq = testText.subSequence(10, 100).toString();
    String itSeq = it.subSequence(10, 100).toString();
    assertThat(itSeq).isEqualTo(strSeq);
  }

  @ParameterizedTest
  @ValueSource(strings = {"UTF_16", "UTF_16BE", "UTF_16LE"})
  void testSeekEnd(String encoding) throws IOException {
    this.prepareFixtures(encoding);
    it.setIndex(it.getEndIndex());
    assertThat(it.current()).isEqualTo(CharacterIterator.DONE);
  }

  @ParameterizedTest
  @ValueSource(strings = {"UTF_16", "UTF_16BE", "UTF_16LE"})
  void testStringEquivalence(String encoding) throws IOException {
    this.prepareFixtures(encoding);
    Random rand = new Random();
    IterableCharSequence strIter = IterableCharSequence.fromString(testText);
    assertThat(it.current()).isEqualTo(strIter.current());
    assertThat(it.getBeginIndex()).isEqualTo(strIter.getBeginIndex());
    assertThat(it.getEndIndex()).isEqualTo(strIter.getEndIndex());
    assertThat(it.first()).isEqualTo(strIter.first());
    assertThat(it.last()).isEqualTo(strIter.last());
    for (int i=0; i < TEXT_SIZE / 4; i++) {
      int idx = rand.nextInt(testText.length() - 6);
      it.setIndex(idx);
      strIter.setIndex(idx);
      assertThat(it.current()).isEqualTo(strIter.current());
      assertThat(it.next()).isEqualTo(strIter.next());
      it.setIndex(idx);
      strIter.setIndex(idx);
      assertThat(it.previous()).isEqualTo(strIter.previous());
      assertThat(it.current()).isEqualTo(strIter.current());
    }
  }
}