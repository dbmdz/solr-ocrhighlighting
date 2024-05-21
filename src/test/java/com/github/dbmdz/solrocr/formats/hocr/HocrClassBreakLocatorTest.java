package com.github.dbmdz.solrocr.formats.hocr;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.dbmdz.solrocr.reader.FileSourceReader;
import com.github.dbmdz.solrocr.reader.SourceReader;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.Test;

class HocrClassBreakLocatorTest {
  private static final Path utf8Path = Paths.get("src/test/resources/data/hocr.html");

  @Test
  void firstNext() throws IOException {
    SourceReader reader = new FileSourceReader(utf8Path, null, 8 * 1024, 8);
    HocrClassBreakLocator it = new HocrClassBreakLocator(reader, "ocrx_word");
    int start = it.following(0);
    int end = it.following(start);
    String tag = reader.readUtf8String(start, end - start);
    assertThat(tag).startsWith("<span class='ocrx_word'");
    assertThat(StringUtils.countMatches(tag, "ocrx_word")).isEqualTo(1);
  }

  @Test
  void next() throws IOException {
    SourceReader reader = new FileSourceReader(utf8Path, null, 8 * 1024, 8);
    HocrClassBreakLocator it = new HocrClassBreakLocator(reader, "ocrx_word");
    int start = it.following(671024);
    int end = it.following(start);
    String tag = reader.readUtf8String(start, end - start);
    assertThat(tag).startsWith("<span class='ocrx_word'");
    assertThat(StringUtils.countMatches(tag, "ocrx_word")).isEqualTo(1);
    assertThat(tag).contains("Entſchuldigung");
  }

  @Test
  void previous() throws IOException {
    SourceReader reader = new FileSourceReader(utf8Path, null, 8 * 1024, 8);
    HocrClassBreakLocator it = new HocrClassBreakLocator(reader, "ocrx_word");
    int end = it.preceding(671287);
    int start = it.preceding(end);
    String tag = reader.readUtf8String(start, end - start);
    assertThat(tag).startsWith("<span class='ocrx_word'");
    assertThat(StringUtils.countMatches(tag, "ocrx_word")).isEqualTo(1);
    assertThat(tag).contains("Entſchuldigung");
  }

  @Test
  void previousLast() throws IOException {
    SourceReader reader = new FileSourceReader(utf8Path, null, 8 * 1024, 8);
    HocrClassBreakLocator it = new HocrClassBreakLocator(reader, "ocrx_word");
    int end = reader.length();
    int start = it.preceding(end);
    String tag = reader.readUtf8String(start, end - start);
    assertThat(tag).startsWith("<span class=\"ocrx_word\"");
    assertThat(StringUtils.countMatches(tag, "ocrx_word")).isEqualTo(1);
    assertThat(tag).contains("omnia.");
  }

  @Test
  void previousFirst() throws IOException {
    SourceReader reader = new FileSourceReader(utf8Path, null, 8 * 1024, 8);
    HocrClassBreakLocator it = new HocrClassBreakLocator(reader, "ocrx_word");
    int idx = it.preceding(1464);
    int end = it.preceding(idx);
    int start = it.preceding(end);
    String tag = reader.readUtf8String(start, end - start);
    assertThat(tag).startsWith("<?xml");
    assertThat(StringUtils.countMatches(tag, "ocrx_word")).isEqualTo(1);
  }
}
