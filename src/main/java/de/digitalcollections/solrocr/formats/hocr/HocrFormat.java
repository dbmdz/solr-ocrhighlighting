package de.digitalcollections.solrocr.formats.hocr;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import de.digitalcollections.solrocr.formats.OcrPassageFormatter;
import de.digitalcollections.solrocr.iter.ContextBreakIterator;
import de.digitalcollections.solrocr.lucene.filters.DehyphenatingHtmlCharFilterFactory;
import de.digitalcollections.solrocr.model.OcrBlock;
import de.digitalcollections.solrocr.model.OcrFormat;
import de.digitalcollections.solrocr.reader.PeekingReader;
import java.io.Reader;
import java.text.BreakIterator;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.apache.lucene.analysis.pattern.PatternReplaceCharFilter;
import org.apache.lucene.analysis.util.CharFilterFactory;

public class HocrFormat implements OcrFormat {
  private static final CharFilterFactory baseFilterFactory = new DehyphenatingHtmlCharFilterFactory();
  private static final Map<OcrBlock, Set<String>> blockClassMapping = ImmutableMap.<OcrBlock, Set<String>>builder()
      .put(OcrBlock.PAGE, ImmutableSet.of("ocr_page"))
      .put(OcrBlock.BLOCK, ImmutableSet.of("ocr_carea", "ocrx_block"))
      .put(OcrBlock.SECTION, ImmutableSet.of("ocr_chapter", "ocr_section", "ocr_subsection", "ocr_subsubsection"))
      .put(OcrBlock.PARAGRAPH, ImmutableSet.of("ocr_par"))
      .put(OcrBlock.LINE, ImmutableSet.of("ocr_line", "ocrx_line"))
      .put(OcrBlock.WORD, ImmutableSet.of("ocrx_word"))
      .build();
  private static final Pattern TITLE_PAT = Pattern.compile("<title>.*?</title>");

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
                                                 boolean absoluteHighlights, boolean alignSpans) {
    return new HocrPassageFormatter(prehHighlightTag, postHighlightTag, absoluteHighlights, alignSpans);
  }

  @Override
  public Reader filter(PeekingReader input, boolean expandAlternatives) {
    Reader filtered = baseFilterFactory.create(input);
    return new PatternReplaceCharFilter(TITLE_PAT, "", filtered);
  }

  @Override
  public boolean hasFormat(String ocrChunk) {
    return blockClassMapping.values().stream()
        .flatMap(Collection::stream)
        .anyMatch(ocrChunk::contains);
  }
}
