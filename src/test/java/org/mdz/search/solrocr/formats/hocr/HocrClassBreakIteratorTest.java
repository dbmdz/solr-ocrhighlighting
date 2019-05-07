package org.mdz.search.solrocr.formats.hocr;

import com.google.common.collect.ImmutableSet;
import com.google.common.io.CharStreams;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.apache.commons.lang3.StringUtils;
import org.apache.lucene.analysis.charfilter.HTMLStripCharFilter;
import org.junit.Test;
import org.mdz.search.solrocr.util.FileBytesCharIterator;
import org.mdz.search.solrocr.util.IterableCharSequence;

import static org.assertj.core.api.Assertions.assertThat;

class HocrClassBreakIteratorTest {
  private static final Path utf8Path = Paths.get("src/test/resources/data/hocr_utf8.html");

  private String stripTags(String val) throws IOException {
    HTMLStripCharFilter filter = new HTMLStripCharFilter(new StringReader(val), ImmutableSet.of("em"));
    return CharStreams.toString(filter).replaceAll("\n", "").trim();
  }

  @Test
  void firstNext() throws IOException {
    IterableCharSequence seq = new FileBytesCharIterator(utf8Path, StandardCharsets.UTF_8);
    HocrClassBreakIterator it = new HocrClassBreakIterator("ocrx_word");
    it.setText(seq);
    int start = it.next();
    int end = it.next();
    String tag = seq.subSequence(start, end).toString();
    assertThat(tag).startsWith("<span class='ocrx_word'");
    assertThat(StringUtils.countMatches(tag, "ocrx_word")).isEqualTo(1);
  }

  @Test
  void next() throws IOException {
    IterableCharSequence seq = new FileBytesCharIterator(utf8Path, StandardCharsets.UTF_8);
    HocrClassBreakIterator it = new HocrClassBreakIterator("ocrx_word");
    it.setText(seq);
    seq.setIndex(670861);
    int start = it.next();
    int end = it.next();
    String tag = seq.subSequence(start, end).toString();
    assertThat(tag).startsWith("<span class='ocrx_word'");
    assertThat(StringUtils.countMatches(tag, "ocrx_word")).isEqualTo(1);
    assertThat(stripTags(tag)).isEqualTo("Entſchuldigung");
  }
}