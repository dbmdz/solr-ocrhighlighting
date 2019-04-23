package org.mdz.search.solrocr.formats.hocr;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.text.BreakIterator;
import java.util.Map;
import java.util.Set;
import org.mdz.search.solrocr.formats.OcrBlock;
import org.mdz.search.solrocr.formats.OcrFormat;
import org.mdz.search.solrocr.formats.OcrPassageFormatter;
import org.mdz.search.solrocr.util.ContextBreakIterator;

public class HocrFormat implements OcrFormat {
  private static final Map<OcrBlock, Set<String>> blockClassMapping = ImmutableMap.of(
      OcrBlock.PAGE, ImmutableSet.of("ocr_page"),
      OcrBlock.BLOCK, ImmutableSet.of("ocr_carea", "ocrx_block"),
      OcrBlock.PARAGRAPH, ImmutableSet.of("ocr_par"),
      OcrBlock.LINE, ImmutableSet.of("ocr_line", "ocrx_line"),
      OcrBlock.WORD, ImmutableSet.of("ocrx_word"));

  private Set<String> breakClasses = blockClassMapping.get(OcrBlock.LINE);
  private int contextSize = 2;

  @Override
  public void setBreakParameters(OcrBlock breakBlock, int contextSize) {
    this.contextSize = contextSize;
    this.breakClasses = blockClassMapping.get(breakBlock);
  }

  @Override
  public BreakIterator getBreakIterator() {
    return new ContextBreakIterator(new HocrClassBreakIterator(breakClasses), contextSize);
  }

  @Override
  public OcrPassageFormatter getPassageFormatter(OcrBlock limitBlock, String prehHighlightTag,
                                                 String postHighlightTag, boolean absoluteHighlights) {
    return new HocrPassageFormatter(
        blockClassMapping.get(limitBlock), prehHighlightTag, postHighlightTag, absoluteHighlights);
  }
}
