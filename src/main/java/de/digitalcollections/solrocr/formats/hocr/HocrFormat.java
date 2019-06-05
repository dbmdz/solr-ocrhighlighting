package de.digitalcollections.solrocr.formats.hocr;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.text.BreakIterator;
import java.util.Map;
import java.util.Set;
import de.digitalcollections.solrocr.formats.OcrBlock;
import de.digitalcollections.solrocr.formats.OcrFormat;
import de.digitalcollections.solrocr.formats.OcrPassageFormatter;
import de.digitalcollections.solrocr.util.ContextBreakIterator;

public class HocrFormat implements OcrFormat {
  private static final Map<OcrBlock, Set<String>> blockClassMapping = ImmutableMap.<OcrBlock, Set<String>>builder()
      .put(OcrBlock.PAGE, ImmutableSet.of("ocr_page"))
      .put(OcrBlock.BLOCK, ImmutableSet.of("ocr_carea", "ocrx_block"))
      .put(OcrBlock.SECTION, ImmutableSet.of("ocr_chapter", "ocr_section", "ocr_subsection", "ocr_subsubsection"))
      .put(OcrBlock.PARAGRAPH, ImmutableSet.of("ocr_par"))
      .put(OcrBlock.LINE, ImmutableSet.of("ocr_line", "ocrx_line"))
      .put(OcrBlock.WORD, ImmutableSet.of("ocrx_word"))
      .build();

  @Override
  public BreakIterator getBreakIterator(OcrBlock breakBlock, OcrBlock limitBlock, int contextSize) {
    Set<String> breakClasses = blockClassMapping.get(breakBlock);
    Set<String> limitClasses = limitBlock == null ? null : blockClassMapping.get(limitBlock);
    return new ContextBreakIterator(
        new HocrClassBreakIterator(breakClasses),
        limitClasses != null ? new HocrClassBreakIterator(limitClasses) : null,
        contextSize);
  }

  @Override
  public OcrPassageFormatter getPassageFormatter(String prehHighlightTag, String postHighlightTag,
                                                 boolean absoluteHighlights) {
    return new HocrPassageFormatter(prehHighlightTag, postHighlightTag, absoluteHighlights);
  }
}
