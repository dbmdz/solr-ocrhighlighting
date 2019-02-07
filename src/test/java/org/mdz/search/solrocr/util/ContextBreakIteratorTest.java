package org.mdz.search.solrocr.util;

import java.io.IOException;
import java.nio.file.Paths;
import java.text.BreakIterator;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Java6Assertions.assertThat;

class ContextBreakIteratorTest {
  private FileCharIterator text;
  private ContextBreakIterator it;

  @BeforeEach
  void setUp() throws IOException {
    text = new FileCharIterator(Paths.get("src/test/resources/data/ocr.xml"));
    BreakIterator baseIter = new TagBreakIterator("w");
    it = new ContextBreakIterator(baseIter, 5);
    it.setText(this.text);
  }

  @Test
  void testContext() {
    int center = 1062;
    int start = it.preceding(center);
    int end = it.following(center);
    assertThat(start).isLessThan(end);
    String snippet = text.subSequence(start, end).toString();
    assertThat(StringUtils.countMatches(snippet, "<w")).isEqualTo(2 * 5 + 1);
    assertThat(StringUtils.countMatches(snippet, "</w>")).isEqualTo(2 * 5 + 1);
  }
}