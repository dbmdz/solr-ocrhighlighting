package org.mdz.search.solrocr.formats.alto;

import com.google.common.collect.ImmutableMap;
import java.text.BreakIterator;
import java.util.Map;
import org.mdz.search.solrocr.formats.OcrBlock;
import org.mdz.search.solrocr.formats.OcrFormat;
import org.mdz.search.solrocr.formats.OcrPassageFormatter;
import org.mdz.search.solrocr.util.ContextBreakIterator;
import org.mdz.search.solrocr.util.TagBreakIterator;

public class AltoFormat implements OcrFormat {
  private static final Map<OcrBlock, String> blockTagMapping = ImmutableMap.of(
      OcrBlock.PAGE, "Page",
      //OcrBlock.SECTION, "",
      OcrBlock.BLOCK, "TextBlock",
      OcrBlock.LINE, "TextLine",
      OcrBlock.WORD, "String");

  @Override
  public BreakIterator getBreakIterator(OcrBlock breakBlock, OcrBlock limitBlock, int contextSize) {
    String breakTag = blockTagMapping.get(breakBlock);
    String limitTag = limitBlock == null ? null : blockTagMapping.get(limitBlock);
    return new ContextBreakIterator(new TagBreakIterator(breakTag), new TagBreakIterator(limitTag), contextSize);
  }

  @Override
  public OcrPassageFormatter getPassageFormatter(String prehHighlightTag, String postHighlightTag,
                                                 boolean absoluteHighlights) {
    return new AltoPassageFormatter(prehHighlightTag, postHighlightTag, absoluteHighlights);
  }
}
