package de.digitalcollections.solrocr.util;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.common.collect.ImmutableList;
import de.digitalcollections.solrocr.iter.MultiFileBytesCharIterator;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class MultiFileBytesCharIteratorTest {
  private static final Pattern OFFSET_PAT = Pattern.compile("\\s(.+?)⚑(\\d+)");

  private final List<Path> utfPaths = ImmutableList.of(
      Paths.get("src/test/resources/data/multi_txt/part_1.txt"),
      Paths.get("src/test/resources/data/multi_txt/part_2.txt"),
      Paths.get("src/test/resources/data/multi_txt/part_3.txt"),
      Paths.get("src/test/resources/data/multi_txt/part_4.txt"));
  private final Path utf8CompletePath;

  private final Map<Integer, Character> utf8Chars;
  private final MultiFileBytesCharIterator utf8It;
  private final String utf8Text;
  private MultiFileBytesCharIterator asciiIt;
  private String asciiText;

  public MultiFileBytesCharIteratorTest() throws IOException {
    utf8It = new MultiFileBytesCharIterator(utfPaths, StandardCharsets.UTF_8, null);
    utf8CompletePath = Paths.get("src/test/resources/data/multi_txt/complete.txt");
    utf8Text = new String(Files.readAllBytes(utf8CompletePath), StandardCharsets.UTF_8);
    Matcher m = OFFSET_PAT.matcher(new String(
        Files.readAllBytes(Paths.get("src/test/resources/data/multi_txt/index.txt")),
        StandardCharsets.UTF_8));
    utf8Chars = new HashMap<>();
    while (m.find()) {
      String charStr = m.group(1);
      String offsetStr = m.group(2);
      int offset = Integer.parseInt(offsetStr);
      utf8Chars.put(offset, charStr.charAt(0));
    }
  }

  @Test
  public void testLength() throws IOException {
    assertThat(utf8It.length()).isEqualTo(Files.size(utf8CompletePath));
  }

  @Test
  public void testFirst() {
    // TODO: Test with file that has BOM
    // TODO: Test with file that has multiple bytes for the first codepoint
    // TODO: Test with file that requires a surrogate pair for the first codepoint
    assertThat(utf8It.first()).isEqualTo('e');
  }

  @Test
  public void testLast() {
    // TODO: Test with file that has BOM
    // TODO: Test with file that has multiple bytes for the last codepoint
    // TODO: Test with file that requires a surrogate pair for the last codepoint
    assertThat(utf8It.last()).isEqualTo('g');
  }
}
