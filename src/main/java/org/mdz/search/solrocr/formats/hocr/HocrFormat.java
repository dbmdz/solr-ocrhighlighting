package org.mdz.search.solrocr.formats.hocr;

import com.google.common.collect.ImmutableMap;
import java.text.BreakIterator;
import java.util.Map;
import org.mdz.search.solrocr.formats.OcrBlock;
import org.mdz.search.solrocr.formats.OcrFormat;
import org.mdz.search.solrocr.formats.OcrPassageFormatter;
import org.mdz.search.solrocr.util.ContextBreakIterator;

public class HocrFormat implements OcrFormat {
  private static final Map<OcrBlock, String> blockClassMapping = ImmutableMap.of(
      OcrBlock.PAGE, "ocr_page",
      OcrBlock.BLOCK, "ocr_carea",
      OcrBlock.PARAGRAPH, "ocr_par",
      OcrBlock.LINE, "ocr_line",
      OcrBlock.WORD, "ocrx_word");

  private String breakClass = blockClassMapping.get(OcrBlock.LINE);
  private int contextSize = 2;

  @Override
  public void setBreakParameters(OcrBlock breakBlock, int contextSize) {
    this.contextSize = contextSize;
    this.breakClass = blockClassMapping.get(breakBlock);
  }

  @Override
  public BreakIterator getBreakIterator() {
    return new ContextBreakIterator(new HocrClassBreakIterator(breakClass), contextSize);
  }

  @Override
  public OcrPassageFormatter getPassageFormatter(OcrBlock limitBlock, String prehHighlightTag,
                                                 String postHighlightTag, boolean absoluteHighlights) {
    return new HocrPassageFormatter(breakClass, blockClassMapping.get(limitBlock), prehHighlightTag, postHighlightTag,
                                    absoluteHighlights);
  }
}
