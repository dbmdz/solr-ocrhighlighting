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
      OcrBlock.SECTION, "s",
      OcrBlock.BLOCK, "b",
      OcrBlock.LINE, "l",
      OcrBlock.WORD, "w");

  @Override
  public BreakIterator getBreakIterator(OcrBlock breakBlock, OcrBlock limitBlock, int contextSize) {
    String breakTag = blockTagMapping.get(breakBlock);
    String limitTag = limitBlock == null ? null : blockTagMapping.get(limitBlock);
    return new ContextBreakIterator(
        new TagBreakIterator(breakTag),
        limitTag != null ? new TagBreakIterator(limitTag) : null,
        contextSize);
  }

  @Override
  public OcrPassageFormatter getPassageFormatter(String prehHighlightTag, String postHighlightTag,
                                                 boolean absoluteHighlights) {
    return new MiniOcrPassageFormatter(prehHighlightTag, postHighlightTag, absoluteHighlights);
  }
}
