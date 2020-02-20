package de.digitalcollections.solrocr.util;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.common.collect.ImmutableSet;
import de.digitalcollections.solrocr.formats.hocr.HocrClassBreakIterator;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.BreakIterator;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.lucene.analysis.charfilter.HTMLStripCharFilter;
import org.junit.jupiter.api.Test;

class ContextBreakIteratorTest {
  private static final Path utf8Path = Paths.get("src/test/resources/data/miniocr.xml");

  private String stripTags(String val) throws IOException {
    HTMLStripCharFilter filter = new HTMLStripCharFilter(new StringReader(val), ImmutableSet.of("em"));
    return IOUtils.toString(filter).replaceAll("\n", "").trim();
  }

  @Test
  void testContext() throws IOException {
    IterableCharSequence seq = new FileBytesCharIterator(utf8Path, StandardCharsets.UTF_8, null);
    BreakIterator baseIter = new TagBreakIterator("w");
    BreakIterator limitIter = new TagBreakIterator("b");
    ContextBreakIterator it = new ContextBreakIterator(baseIter, limitIter, 5);
    it.setText(seq);
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
    BreakIterator baseIter = new HocrClassBreakIterator("ocr_line");
    BreakIterator limitIter = new HocrClassBreakIterator("ocrx_block");
    ContextBreakIterator it = new ContextBreakIterator(baseIter, limitIter, 5);
    it.setText(seq);
    int start = it.preceding(5352801);
    int end = it.following(5352801 + "Japan</span>".length());
    assertThat(start).isLessThan(end);
    String snippet = seq.subSequence(start, end).toString();
    assertThat(StringUtils.countMatches(snippet, "ocr_line")).isEqualTo(1 + 1 + 5);
    assertThat(snippet).doesNotContain("ocr_page");
    assertThat(snippet).containsOnlyOnce("ocrx_block");
  }
}