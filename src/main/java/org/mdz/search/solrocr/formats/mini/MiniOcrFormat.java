package org.mdz.search.solrocr.formats.mini;

import com.google.common.collect.ImmutableMap;
import java.text.BreakIterator;
import java.util.Map;
import org.mdz.search.solrocr.formats.OcrBlock;
import org.mdz.search.solrocr.formats.OcrFormat;
import org.mdz.search.solrocr.formats.OcrPassageFormatter;
import org.mdz.search.solrocr.util.ContextBreakIterator;
import org.mdz.search.solrocr.util.TagBreakIterator;

public class MiniOcrFormat implements OcrFormat {
  private static final Map<OcrBlock, String> blockTagMapping = ImmutableMap.of(
      OcrBlock.PAGE, "p",
      OcrBlock.BLOCK, "b",
      OcrBlock.LINE, "l",
      OcrBlock.WORD, "w");

  private String breakTag = blockTagMapping.get(OcrBlock.LINE);
  private int contextSize = 2;

  @Override
  public void setBreakParameters(OcrBlock breakBlock, int contextSize) {
    this.contextSize = contextSize;
    this.breakTag = blockTagMapping.get(breakBlock);
  }

  @Override
  public BreakIterator getBreakIterator() {
    return new ContextBreakIterator(new TagBreakIterator(breakTag), contextSize);
  }

  @Override
  public OcrPassageFormatter getPassageFormatter(OcrBlock limitBlock, String prehHighlightTag, String postHighlightTag) {
    return new MiniOcrPassageFormatter(breakTag, blockTagMapping.get(limitBlock), prehHighlightTag, postHighlightTag);
  }
}
