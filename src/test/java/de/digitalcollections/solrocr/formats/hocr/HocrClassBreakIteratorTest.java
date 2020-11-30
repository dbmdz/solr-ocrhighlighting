package de.digitalcollections.solrocr.formats.hocr;

import de.digitalcollections.solrocr.iter.FileBytesCharIterator;
import de.digitalcollections.solrocr.iter.IterableCharSequence;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HocrClassBreakIteratorTest {
  private static final Path utf8Path = Paths.get("src/test/resources/data/hocr.html");

  @Test
  void firstNext() throws IOException {
    IterableCharSequence seq = new FileBytesCharIterator(utf8Path, StandardCharsets.UTF_8, null);
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
    IterableCharSequence seq = new FileBytesCharIterator(utf8Path, StandardCharsets.UTF_8, null);
    HocrClassBreakIterator it = new HocrClassBreakIterator("ocrx_word");
    it.setText(seq);
    seq.setIndex(671024);
    int start = it.next();
    int end = it.next();
    String tag = seq.subSequence(start, end).toString();
    assertThat(tag).startsWith("<span class='ocrx_word'");
    assertThat(StringUtils.countMatches(tag, "ocrx_word")).isEqualTo(1);
    assertThat(tag).contains("Entſchuldigung");
  }

  @Test
  void previous() throws IOException {
    IterableCharSequence seq = new FileBytesCharIterator(utf8Path, StandardCharsets.UTF_8, null);
    HocrClassBreakIterator it = new HocrClassBreakIterator("ocrx_word");
    it.setText(seq);
    seq.setIndex(671287);
    int end = it.previous();
    int start = it.previous();
    String tag = seq.subSequence(start, end).toString();
    assertThat(tag).startsWith("<span class='ocrx_word'");
    assertThat(StringUtils.countMatches(tag, "ocrx_word")).isEqualTo(1);
    assertThat(tag).contains("Entſchuldigung");
  }

  @Test
  void previousLast() throws IOException {
    IterableCharSequence seq = new FileBytesCharIterator(utf8Path, StandardCharsets.UTF_8, null);
    HocrClassBreakIterator it = new HocrClassBreakIterator("ocrx_word");
    it.setText(seq);
    int end = it.last();
    int start = it.previous();
    String tag = seq.subSequence(start, end).toString();
    assertThat(tag).startsWith("<span class=\"ocrx_word\"");
    assertThat(StringUtils.countMatches(tag, "ocrx_word")).isEqualTo(1);
    assertThat(tag).contains("omnia.");
  }
}