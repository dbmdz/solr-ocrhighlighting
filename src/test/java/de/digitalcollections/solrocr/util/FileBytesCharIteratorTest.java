package de.digitalcollections.solrocr.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.CharacterIterator;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import de.digitalcollections.solrocr.formats.mini.MiniOcrByteOffsetsParser;

import static org.assertj.core.api.Assertions.assertThat;

class FileBytesCharIteratorTest {
  private static final Pattern OFFSET_PAT = Pattern.compile("\\s(.+?)⚑(\\d+)");

  private final Path ocrPath = Paths.get("src/test/resources/data/miniocr_utf8.xml");
  private Map<Integer, String> words;
  private FileBytesCharIterator it;

  public FileBytesCharIteratorTest() throws IOException {
    it = new FileBytesCharIterator(ocrPath);
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    MiniOcrByteOffsetsParser.parse(Files.readAllBytes(ocrPath), bos);
    String text = bos.toString(StandardCharsets.UTF_8.toString());
    this.words = new HashMap<>();
    Matcher m = OFFSET_PAT.matcher(text);
    while (m.find()) {
      String word = m.group(1);
      String offsetStr = m.group(2);
      int offset = Integer.parseInt(offsetStr);
      words.put(offset, word);
    }
  }

  @Test
  public void testAscii() throws IOException {
    Path asciiPath = Paths.get("src/test/resources/data/hocr_escaped.html");
    FileBytesCharIterator asciiIt = new FileBytesCharIterator(asciiPath);
    StringBuilder sb = new StringBuilder();
    String text = new String(Files.readAllBytes(asciiPath), StandardCharsets.US_ASCII);
    int start = 0;
    int charIdx = 0;
    char c = asciiIt.first();
    sb.append(c);
    charIdx++;
    while ((c = asciiIt.next()) != CharacterIterator.DONE) {
      sb.append(c);
      charIdx++;
      if (sb.length() == 100) {
        assertThat(sb.toString()).isEqualTo(text.substring(start, charIdx));
        start = charIdx;
        sb = new StringBuilder();
      }
    }
  }

  @Test
  public void testLength() throws IOException {
    assertThat(it.length()).isEqualTo(Files.size(ocrPath));
  }

  @Test
  public void testCharAt() throws IOException {
    // TODO: Test with file that has BOM
    for (Entry<Integer, String> e : words.entrySet()) {
      char c = it.charAt(e.getKey());
      assertThat(c).isEqualTo(e.getValue().charAt(0));
    }
  }

  @Test
  public void testSubSequence() {
    for (Entry<Integer, String> e : words.entrySet()) {
      int start = e.getKey();
      int end = start + e.getValue().getBytes(StandardCharsets.UTF_8).length;
      String s = it.subSequence(start, end).toString();
      assertThat(s).isEqualTo(e.getValue());
    }
  }

  @Test
  public void testFirst() {
    // TODO: Test with file that has BOM
    // TODO: Test with file that has multiple bytes for the first codepoint
    // TODO: Test with file that requires a surrogate pair for the first codepoint
    assertThat(it.first()).isEqualTo('<');
  }

  @Test
  public void testLast() {
    // TODO: Test with file that has BOM
    // TODO: Test with file that has multiple bytes for the last codepoint
    // TODO: Test with file that requires a surrogate pair for the last codepoint
    assertThat(it.last()).isEqualTo('\n');
  }

  @Test
  public void testNext() throws IOException {
    StringBuilder sb = new StringBuilder();
    String text = new String(Files.readAllBytes(ocrPath), StandardCharsets.UTF_8);
    int start = 0;
    int charIdx = 0;
    char c = it.first();
    sb.append(c);
    charIdx++;
    while ((c = it.next()) != CharacterIterator.DONE) {
      sb.append(c);
      charIdx++;
      if (sb.length() == 100) {
        assertThat(sb.toString()).isEqualTo(text.substring(start, charIdx));
        start = charIdx;
        sb = new StringBuilder();
      }
    }
  }

  @Test
  public void testPrevious() throws IOException {
    StringBuilder sb = new StringBuilder();
    String text = new String(Files.readAllBytes(ocrPath), StandardCharsets.UTF_8);
    int end = text.length();
    int charIdx = text.length();
    char c = it.last();
    sb.insert(0, c);
    charIdx--;
    while ((c = it.previous()) != CharacterIterator.DONE) {
      sb.insert(0, c);
      charIdx--;
      if (sb.length() == 100) {
        assertThat(sb.toString()).isEqualTo(text.substring(charIdx, end));
        end = charIdx;
        sb = new StringBuilder();
      }
    }
  }

  @Test
  public void testSetIndex() throws IOException {
    for (Entry<Integer, String> e : this.words.entrySet()) {
      char c = it.setIndex(e.getKey());
      assertThat(c).isEqualTo(e.getValue().charAt(0));
    }
  }
}