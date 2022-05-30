package com.github.dbmdz.solrocr.formats.hocr;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.dbmdz.solrocr.iter.FileBytesCharIterator;
import com.github.dbmdz.solrocr.iter.IterableCharSequence;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.Test;

class HocrClassBreakLocatorTest {
  private static final Path utf8Path = Paths.get("src/test/resources/data/hocr.html");

  @Test
  void firstNext() throws IOException {
    IterableCharSequence seq = new FileBytesCharIterator(utf8Path, StandardCharsets.UTF_8, null);
    HocrClassBreakLocator it = new HocrClassBreakLocator(seq, "ocrx_word");
    int start = it.following(0);
    int end = it.following(start);
    String tag = seq.subSequence(start, end).toString();
    assertThat(tag).startsWith("<span class='ocrx_word'");
    assertThat(StringUtils.countMatches(tag, "ocrx_word")).isEqualTo(1);
  }

  @Test
  void next() throws IOException {
    IterableCharSequence seq = new FileBytesCharIterator(utf8Path, StandardCharsets.UTF_8, null);
    HocrClassBreakLocator it = new HocrClassBreakLocator(seq, "ocrx_word");
    int start = it.following(671024);
    int end = it.following(start);
    String tag = seq.subSequence(start, end).toString();
    assertThat(tag).startsWith("<span class='ocrx_word'");
    assertThat(StringUtils.countMatches(tag, "ocrx_word")).isEqualTo(1);
    assertThat(tag).contains("Entſchuldigung");
  }

  @Test
  void previous() throws IOException {
    IterableCharSequence seq = new FileBytesCharIterator(utf8Path, StandardCharsets.UTF_8, null);
    HocrClassBreakLocator it = new HocrClassBreakLocator(seq, "ocrx_word");
    int end = it.preceding(671287);
    int start = it.preceding(end);
    String tag = seq.subSequence(start, end).toString();
    assertThat(tag).startsWith("<span class='ocrx_word'");
    assertThat(StringUtils.countMatches(tag, "ocrx_word")).isEqualTo(1);
    assertThat(tag).contains("Entſchuldigung");
  }

  @Test
  void previousLast() throws IOException {
    IterableCharSequence seq = new FileBytesCharIterator(utf8Path, StandardCharsets.UTF_8, null);
    HocrClassBreakLocator it = new HocrClassBreakLocator(seq, "ocrx_word");
    int end = seq.length();
    int start = it.preceding(end);
    String tag = seq.subSequence(start, end).toString();
    assertThat(tag).startsWith("<span class=\"ocrx_word\"");
    assertThat(StringUtils.countMatches(tag, "ocrx_word")).isEqualTo(1);
    assertThat(tag).contains("omnia.");
  }

  @Test
  void previousFirst() throws IOException {
    IterableCharSequence seq = new FileBytesCharIterator(utf8Path, StandardCharsets.UTF_8, null);
    HocrClassBreakLocator it = new HocrClassBreakLocator(seq, "ocrx_word");
    seq.setIndex(1464);
    int idx = it.preceding(1464);
    int end = it.preceding(idx);
    int start = it.preceding(end);
    String tag = seq.subSequence(start, end).toString();
    assertThat(tag).startsWith("<?xml");
    assertThat(StringUtils.countMatches(tag, "ocrx_word")).isEqualTo(1);
  }
}
