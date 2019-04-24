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

  @Override
  public BreakIterator getBreakIterator(OcrBlock breakBlock, OcrBlock limitBlock, int contextSize) {
    Set<String> breakClasses = blockClassMapping.get(breakBlock);
    Set<String> limitClasses = blockClassMapping.get(limitBlock);
    return new ContextBreakIterator(new HocrClassBreakIterator(breakClasses), new HocrClassBreakIterator(limitClasses),
                                    contextSize);
  }

  @Override
  public OcrPassageFormatter getPassageFormatter(String prehHighlightTag, String postHighlightTag,
                                                 boolean absoluteHighlights) {
    return new HocrPassageFormatter(prehHighlightTag, postHighlightTag, absoluteHighlights);
  }
}
