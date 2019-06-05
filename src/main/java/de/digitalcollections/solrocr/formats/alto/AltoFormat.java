package de.digitalcollections.solrocr.formats.alto;

import com.google.common.collect.ImmutableMap;
import java.text.BreakIterator;
import java.util.Map;
import de.digitalcollections.solrocr.formats.OcrBlock;
import de.digitalcollections.solrocr.formats.OcrFormat;
import de.digitalcollections.solrocr.formats.OcrPassageFormatter;
import de.digitalcollections.solrocr.util.ContextBreakIterator;
import de.digitalcollections.solrocr.util.TagBreakIterator;

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
