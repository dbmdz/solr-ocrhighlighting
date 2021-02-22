package de.digitalcollections.solrocr.util;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.common.collect.ImmutableSet;
import de.digitalcollections.solrocr.formats.hocr.HocrClassBreakLocator;
import de.digitalcollections.solrocr.iter.BreakLocator;
import de.digitalcollections.solrocr.iter.ContextBreakLocator;
import de.digitalcollections.solrocr.iter.FileBytesCharIterator;
import de.digitalcollections.solrocr.iter.IterableCharSequence;
import de.digitalcollections.solrocr.iter.TagBreakLocator;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.lucene.analysis.charfilter.HTMLStripCharFilter;
import org.junit.jupiter.api.Test;

class ContextBreakLocatorTest {
  private static final Path utf8Path = Paths.get("src/test/resources/data/miniocr.xml");

  private String stripTags(String val) throws IOException {
    HTMLStripCharFilter filter = new HTMLStripCharFilter(new StringReader(val), ImmutableSet.of("em"));
    return IOUtils.toString(filter).replaceAll("\n", "").trim();
  }

  @Test
  void testContext() throws IOException {
    IterableCharSequence seq = new FileBytesCharIterator(utf8Path, StandardCharsets.UTF_8, null);
    TagBreakLocator baseLocator = new TagBreakLocator(seq, "w");
    TagBreakLocator limitLocator = new TagBreakLocator(seq, "b");
    ContextBreakLocator it = new ContextBreakLocator(baseLocator, limitLocator, 5);
    int center = 16283;
    int start = it.preceding(center);
    int end = it.following(center);
    assertThat(start).isLessThan(end);
    String snippet = seq.subSequence(start, end).toString();
    assertThat(StringUtils.countMatches(snippet, "<w")).isEqualTo(2 * 5 + 1);
    assertThat(StringUtils.countMatches(snippet, "</w>")).isEqualTo(2 * 5 + 1);
    assertThat(stripTags(snippet)).isEqualTo("Sgr. 6 Pf, für die viergeſpaltene Petitzeile oder deren Raum berechnet,");
  }

  @Test
  void testContextHonorsLimits() throws IOException {
    IterableCharSequence seq = new FileBytesCharIterator(Paths.get("src/test/resources/data/hocr.html"),
                                                         StandardCharsets.UTF_8, null);
    BreakLocator baseLocator = new HocrClassBreakLocator(seq, "ocr_line");
    BreakLocator limitLocator = new HocrClassBreakLocator(seq, "ocrx_block");
    ContextBreakLocator it = new ContextBreakLocator(baseLocator, limitLocator, 5);
    int start = it.preceding(5352801);
    int end = it.following(5352801 + "Japan</span>".length());
    assertThat(start).isLessThan(end);
    String snippet = seq.subSequence(start, end).toString();
    assertThat(StringUtils.countMatches(snippet, "ocr_line")).isEqualTo(1 + 1 + 5);
    assertThat(snippet).doesNotContain("ocr_page");
    assertThat(snippet).containsOnlyOnce("ocrx_block");
  }

  @Test
  void testCachingDoesntInfluenceResults() throws IOException {
    String lastResult = null;

    int offStart = 42736;
    int offEnd = 42919;
    for (int i=0; i < 3; i++) {
      IterableCharSequence seq = new FileBytesCharIterator(
          Paths.get("src/test/resources/data/bnl_lunion_1865-04-15.xml"), StandardCharsets.UTF_8, null);
      BreakLocator baseLocator = new TagBreakLocator(seq, "TextLine");
      BreakLocator limitLocator = new TagBreakLocator(seq, "TextBlock");
      ContextBreakLocator it = new ContextBreakLocator(baseLocator, limitLocator, 2);
      int start = it.preceding(offStart);
      int end = it.following(offEnd);
      String snippet = seq.subSequence(start, end).toString();
      if (lastResult != null) {
        assertThat(snippet).isEqualTo(lastResult);
      }
      lastResult = snippet;
      offStart += 1;
      offEnd += 1;
    }
  }

  @Test
  void testContextWithHyphenationAndCaching() throws IOException {
    IterableCharSequence seq = new FileBytesCharIterator(
        Paths.get("src/test/resources/data/bnl_lunion_1865-04-15.xml"), StandardCharsets.UTF_8, null);
    BreakLocator baseLocator = new TagBreakLocator(seq, "TextLine");
    BreakLocator limitLocator = new TagBreakLocator(seq, "TextBlock");
    ContextBreakLocator it = new ContextBreakLocator(baseLocator, limitLocator, 2);

    int start = it.preceding(42736);
    int end = it.following(42919);
    assertThat(start).isLessThan(end);
    String snippet = seq.subSequence(start, end).toString();
    // One match line, two following context lines
    assertThat(StringUtils.countMatches(snippet, "<TextLine")).isEqualTo(1 + 2);

    start = it.preceding(43627);
    end = it.following(43849);
    assertThat(start).isLessThan(end);
    // One match line, two following context lines
    snippet = seq.subSequence(start, end).toString();
    assertThat(StringUtils.countMatches(snippet, "<TextLine")).isEqualTo(1 + 2);
  }
}
