package de.digitalcollections.solrocr.formats.mini;

import com.google.common.collect.ImmutableMap;
import de.digitalcollections.solrocr.formats.OcrPassageFormatter;
import de.digitalcollections.solrocr.iter.ContextBreakIterator;
import de.digitalcollections.solrocr.iter.TagBreakIterator;
import de.digitalcollections.solrocr.lucene.filters.DehyphenatingHtmlCharFilterFactory;
import de.digitalcollections.solrocr.model.OcrBlock;
import de.digitalcollections.solrocr.model.OcrFormat;
import de.digitalcollections.solrocr.reader.PeekingReader;
import java.io.Reader;
import java.text.BreakIterator;
import java.util.Map;
import org.apache.lucene.analysis.util.CharFilterFactory;

public class MiniOcrFormat implements OcrFormat {
  private static final CharFilterFactory filterFactory = new DehyphenatingHtmlCharFilterFactory();
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
                                                 boolean absoluteHighlights, boolean alignSpans) {
    return new MiniOcrPassageFormatter(prehHighlightTag, postHighlightTag, absoluteHighlights, alignSpans);
  }

  @Override
  public Reader filter(PeekingReader input) {
    return filterFactory.create(input);
  }

  @Override
  public boolean hasFormat(String ocrChunk) {
    return blockTagMapping.values().stream()
        .anyMatch(
            t -> ocrChunk.contains("<" + t + " ")
              || ocrChunk.contains("<" + t + ">"));
  }
}
