package org.mdz.search.solrocr.formats.hocr;

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
import org.mdz.search.solrocr.util.FileBytesCharIterator;
import org.mdz.search.solrocr.util.FileCharIterator;
import org.mdz.search.solrocr.util.IterableCharSequence;

import static org.assertj.core.api.Assertions.assertThat;

class HocrClassBreakIteratorTest {
  private static final Path utf16Path = Paths.get("src/test/resources/data/hocr_utf16.html");
  private static final Path utf8Path = Paths.get("src/test/resources/data/hocr_utf8.html");

  static Stream<IterableCharSequence> charSeq() throws IOException {
    return Stream.of(new FileCharIterator(utf16Path), new FileBytesCharIterator(utf8Path));
  }

  private String stripTags(String val) throws IOException {
    HTMLStripCharFilter filter = new HTMLStripCharFilter(new StringReader(val), ImmutableSet.of("em"));
    return CharStreams.toString(filter).replaceAll("\n", "").trim();
  }

  @ParameterizedTest
  @MethodSource("charSeq")
  void firstNext(IterableCharSequence seq) {
    HocrClassBreakIterator it = new HocrClassBreakIterator("ocrx_word");
    it.setText(seq);
    int start = it.next();
    int end = it.next();
    String tag = seq.subSequence(start, end).toString();
    assertThat(tag).startsWith("<span class='ocrx_word'");
    assertThat(StringUtils.countMatches(tag, "ocrx_word")).isEqualTo(1);
  }

  @ParameterizedTest
  @MethodSource("charSeq")
  void next(IterableCharSequence seq) throws IOException {
    HocrClassBreakIterator it = new HocrClassBreakIterator("ocrx_word");
    it.setText(seq);
    if (seq instanceof FileBytesCharIterator) {
      seq.setIndex(670861);
    } else {
      seq.setIndex(669022);
    }
    int start = it.next();
    int end = it.next();
    String tag = seq.subSequence(start, end).toString();
    assertThat(tag).startsWith("<span class='ocrx_word'");
    assertThat(StringUtils.countMatches(tag, "ocrx_word")).isEqualTo(1);
    assertThat(stripTags(tag)).isEqualTo("Entſchuldigung");
  }
}