package org.mdz.search.solrocr.util;

import com.google.common.collect.ImmutableSet;
import com.google.common.io.CharStreams;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;
import org.apache.commons.lang3.StringUtils;
import org.apache.lucene.analysis.charfilter.HTMLStripCharFilter;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Java6Assertions.assertThat;

class TagBreakIteratorTest {

  private static final Path utf16Path = Paths.get("src/test/resources/data/31337_ocr.xml");
  private static final Path utf8Path = Paths.get("src/test/resources/data/miniocr_utf8.xml");

  static Stream<IterableCharSequence> charSeq() throws IOException {
    return Stream.of(new FileCharIterator(utf16Path), new FileBytesCharIterator(utf8Path));
  }

  private String stripTags(String val) throws IOException {
    HTMLStripCharFilter filter = new HTMLStripCharFilter(new StringReader(val), ImmutableSet.of("em"));
    return CharStreams.toString(filter).replaceAll("\n", "");
  }

  @ParameterizedTest
  @MethodSource("charSeq")
  void firstNext(IterableCharSequence seq) {
    TagBreakIterator it = new TagBreakIterator("w");
    it.setText(seq);
    int start = it.next();
    int end = it.next();
    String tag = seq.subSequence(start, end).toString();
    assertThat(tag).startsWith("<w");
    assertThat(StringUtils.countMatches(tag, "<w")).isEqualTo(1);
    assertThat(StringUtils.countMatches(tag, "</w>")).isEqualTo(1);
  }

  @ParameterizedTest
  @MethodSource("charSeq")
  void next(IterableCharSequence seq) throws IOException {
    TagBreakIterator it = new TagBreakIterator("w");
    it.setText(seq);
    if (seq instanceof FileBytesCharIterator) {
      seq.setIndex(8267);
    } else {
      seq.setIndex(8192);
    }
    int start = it.next();
    int end = it.next();
    String tag = seq.subSequence(start, end).toString();
    assertThat(tag).startsWith("<w");
    assertThat(StringUtils.countMatches(tag, "<w")).isEqualTo(1);
    assertThat(StringUtils.countMatches(tag, "</w>")).isEqualTo(1);
    assertThat(stripTags(tag)).isEqualTo("der ");
  }
}