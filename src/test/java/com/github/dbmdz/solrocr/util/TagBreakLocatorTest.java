package com.github.dbmdz.solrocr.util;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.dbmdz.solrocr.breaklocator.TagBreakLocator;
import com.github.dbmdz.solrocr.reader.FileSourceReader;
import com.github.dbmdz.solrocr.reader.SourceReader;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.lucene.analysis.charfilter.HTMLStripCharFilter;
import org.junit.jupiter.api.Test;

class TagBreakLocatorTest {

  private static final Path utf8Path = Paths.get("src/test/resources/data/miniocr.xml");

  private String stripTags(String val) throws IOException {
    HTMLStripCharFilter filter =
        new HTMLStripCharFilter(new StringReader(val), ImmutableSet.of("em"));
    return IOUtils.toString(filter).replaceAll("\n", "");
  }

  @Test
  void firstNext() throws IOException {
    SourceReader reader = new FileSourceReader(utf8Path, null, 8 * 1024, 8);
    TagBreakLocator it = new TagBreakLocator(reader, "w");
    int start = it.following(0);
    int end = it.following(start);
    String tag = reader.readUtf8String(start, end - start);
    assertThat(tag).startsWith("<w");
    assertThat(StringUtils.countMatches(tag, "<w")).isEqualTo(1);
    assertThat(StringUtils.countMatches(tag, "</w>")).isEqualTo(1);
  }

  @Test
  void next() throws IOException {
    SourceReader reader = new FileSourceReader(utf8Path, null, 8 * 1024, 8);
    TagBreakLocator it = new TagBreakLocator(reader, "w");
    int start = it.following(8267);
    int end = it.following(start);
    String tag = reader.readUtf8String(start, end - start);
    assertThat(tag).startsWith("<w");
    assertThat(StringUtils.countMatches(tag, "<w")).isEqualTo(1);
    assertThat(StringUtils.countMatches(tag, "</w>")).isEqualTo(1);
    assertThat(stripTags(tag)).isEqualTo("der ");
  }

  @Test
  void lastPrevious() throws IOException {
    SourceReader reader = new FileSourceReader(utf8Path, null, 8 * 1024, 8);
    TagBreakLocator it = new TagBreakLocator(reader, "w");
    int end = reader.length() - 1;
    int start = it.preceding(reader.length() - 1);
    String tag = reader.readUtf8String(start, end - start);
    assertThat(tag).startsWith("<w");
    assertThat(StringUtils.countMatches(tag, "<w")).isEqualTo(1);
    assertThat(StringUtils.countMatches(tag, "</w>")).isEqualTo(1);
    assertThat(tag).contains(">doch<");
  }

  @Test
  void previous() throws IOException {
    SourceReader reader = new FileSourceReader(utf8Path, null, 8 * 1024, 8);
    TagBreakLocator it = new TagBreakLocator(reader, "w");
    int end = 2872126;
    int start = it.preceding(end);
    String tag = reader.readUtf8String(start, end - start);
    assertThat(tag).startsWith("<w");
    assertThat(StringUtils.countMatches(tag, "<w")).isEqualTo(1);
    assertThat(StringUtils.countMatches(tag, "</w>")).isEqualTo(1);
    assertThat(tag).contains(">Wahlanſprache<");
  }

  @Test
  void previousFirst() throws IOException {
    SourceReader reader = new FileSourceReader(utf8Path, null, 8 * 1024, 8);
    TagBreakLocator it = new TagBreakLocator(reader, "w");
    int idx = it.preceding(293);
    idx = it.preceding(idx);
    idx = it.preceding(idx);
    int end = it.preceding(idx);
    int start = it.preceding(end);
    String tag = reader.readUtf8String(start, end - start);
    assertThat(tag).startsWith("<?xml");
    assertThat(StringUtils.countMatches(tag, "<w")).isEqualTo(0);
    assertThat(StringUtils.countMatches(tag, "</w>")).isEqualTo(0);
  }
}
