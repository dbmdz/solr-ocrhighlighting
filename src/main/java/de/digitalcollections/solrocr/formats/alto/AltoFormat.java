package de.digitalcollections.solrocr.formats.alto;

import com.google.common.collect.ImmutableMap;
import de.digitalcollections.solrocr.model.OcrBlock;
import de.digitalcollections.solrocr.model.OcrFormat;
import de.digitalcollections.solrocr.formats.OcrPassageFormatter;
import de.digitalcollections.solrocr.iter.ContextBreakIterator;
import de.digitalcollections.solrocr.iter.TagBreakIterator;
import java.io.Reader;
import java.text.BreakIterator;
import java.util.Map;
import org.apache.lucene.analysis.util.CharFilterFactory;

public class AltoFormat implements OcrFormat {
  private static final CharFilterFactory filterFactory = new AltoCharFilterFactory();
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

  @Override
  public Reader filter(Reader input) {
    return filterFactory.create(input);
  }

  @Override
  public boolean hasFormat(String ocrChunk) {
    // Check if the chunk contains any ALTO tags
    return ocrChunk.contains("<alto")
        || blockTagMapping.values().stream()
            .anyMatch(t -> ocrChunk.contains("<" + t));
  }
}
