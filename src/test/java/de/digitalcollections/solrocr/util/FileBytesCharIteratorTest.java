package de.digitalcollections.solrocr.util;

import static org.assertj.core.api.Assertions.assertThat;

import de.digitalcollections.solrocr.iter.FileBytesCharIterator;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class FileBytesCharIteratorTest {
  private static final Pattern OFFSET_PAT = Pattern.compile("\\s(.+?)⚑(\\d+)");

  private final Path ocrPath = Paths.get("src/test/resources/data/miniocr.xml");
  private final Map<Integer, String> words;
  private final FileBytesCharIterator it;

  public FileBytesCharIteratorTest() throws IOException {
    it = new FileBytesCharIterator(ocrPath, null);
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
  public void testLength() throws IOException {
    assertThat(it.length()).isEqualTo(Files.size(ocrPath));
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
}