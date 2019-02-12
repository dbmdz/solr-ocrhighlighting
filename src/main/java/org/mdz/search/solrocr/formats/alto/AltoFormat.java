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

  private String breakTag = blockTagMapping.get(OcrBlock.LINE);
  private int contextSize = 2;

  @Override
  public void setBreakParameters(OcrBlock breakBlock, int contextSize) {
    this.breakTag = blockTagMapping.get(breakBlock);
    this.contextSize = contextSize;
  }

  @Override
  public BreakIterator getBreakIterator() {
    return new ContextBreakIterator(new TagBreakIterator(breakTag), contextSize);
  }

  @Override
  public OcrPassageFormatter getPassageFormatter(OcrBlock limitBlock, String prehHighlightTag,
      String postHighlightTag) {
    return new AltoPassageFormatter(breakTag, blockTagMapping.get(limitBlock), prehHighlightTag, postHighlightTag);
  }
}
