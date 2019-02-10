package org.mdz.search.solrocr.util;

import com.google.common.collect.ImmutableSet;
import com.google.common.io.CharStreams;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.BreakIterator;
import java.util.stream.Stream;
import org.apache.commons.lang3.StringUtils;
import org.apache.lucene.analysis.charfilter.HTMLStripCharFilter;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Java6Assertions.assertThat;

class ContextBreakIteratorTest {
  private static final Path utf16Path = Paths.get("src/test/resources/data/31337_ocr.xml");
  private static final Path utf8Path = Paths.get("src/test/resources/data/miniocr_utf8.xml");

  static Stream<IterableCharSequence> charSeq() throws IOException {
    return Stream.of(new FileCharIterator(utf16Path), new FileBytesCharIterator(utf8Path));
  }

  private String stripTags(String val) throws IOException {
    HTMLStripCharFilter filter = new HTMLStripCharFilter(new StringReader(val), ImmutableSet.of("em"));
    return CharStreams.toString(filter).replaceAll("\n", "").trim();
  }

  @ParameterizedTest
  @MethodSource("charSeq")
  void testContext(IterableCharSequence seq) throws IOException {
    BreakIterator baseIter = new TagBreakIterator("w");
    ContextBreakIterator it = new ContextBreakIterator(baseIter, 5);
    it.setText(seq);
    int center;
    if (seq instanceof FileBytesCharIterator) {
      center = 16254;
    } else {
      center = 16126;
    }
    int start = it.preceding(center);
    int end = it.following(center);
    assertThat(start).isLessThan(end);
    String snippet = seq.subSequence(start, end).toString();
    assertThat(StringUtils.countMatches(snippet, "<w")).isEqualTo(2 * 5 + 1);
    assertThat(StringUtils.countMatches(snippet, "</w>")).isEqualTo(2 * 5 + 1);
    assertThat(stripTags(snippet)).isEqualTo("Sgr. 6 Pf, für die viergeſpaltene Petitzeile oder deren Raum berechnet,");
  }
}