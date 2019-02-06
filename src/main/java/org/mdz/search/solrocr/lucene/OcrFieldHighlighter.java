package org.mdz.search.solrocr.lucene;

import java.io.IOException;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.search.uhighlight.AnalysisOffsetStrategy;
import org.apache.lucene.search.uhighlight.FieldHighlighter;
import org.apache.lucene.search.uhighlight.FieldOffsetStrategy;
import org.apache.lucene.search.uhighlight.OffsetsEnum;
import org.apache.lucene.search.uhighlight.Passage;
import org.apache.lucene.search.uhighlight.PassageScorer;
import org.mdz.search.solrocr.util.ContextBreakIterator;
import org.mdz.search.solrocr.util.FileCharIterator;
import org.mdz.search.solrocr.util.TagBreakIterator;

public class OcrFieldHighlighter extends FieldHighlighter {
  public OcrFieldHighlighter(String field, FieldOffsetStrategy fieldOffsetStrategy, PassageScorer passageScorer,
                             int maxPassages, int maxNoHighlightPassages, String contextTag, int contextSize) {
    super(field, fieldOffsetStrategy, new ContextBreakIterator(new TagBreakIterator(contextTag), contextSize),
          passageScorer, maxPassages, maxNoHighlightPassages, new OcrPassageFormatter(contextTag));
    if (fieldOffsetStrategy instanceof AnalysisOffsetStrategy) {
      throw new RuntimeException("AnalysisOffsetStrategy is not supported for OCR fields.");
    }
  }

  /**
   * The primary method -- highlight this doc, assuming a specific field and given this content.
   */
  public OcrSnippet[] highlightFieldForDoc(LeafReader reader, int docId, FileCharIterator content) throws IOException {
    // note: it'd be nice to accept a CharSequence for content, but we need a CharacterIterator impl for it.
    if (content.length() == 0) {
      return null; // nothing to do
    }

    breakIterator.setText(content);

    // NOTE: We can only pass null for the content string here because we excluded analysis-based offset sources
    //       in the constructor
    try (OffsetsEnum offsetsEnums = fieldOffsetStrategy.getOffsetsEnum(reader, docId, null)) {

      // Highlight the offsetsEnum list against the content to produce Passages.
      Passage[] passages = highlightOffsetsEnums(offsetsEnums);// and breakIterator & scorer

      // Format the resulting Passages.
      if (passages.length == 0) {
        // no passages were returned, so ask for a default summary
        passages = getSummaryPassagesNoHighlight(maxNoHighlightPassages == -1 ? maxPassages : maxNoHighlightPassages);
      }

      if (passages.length > 0) {
        return ((OcrPassageFormatter) passageFormatter).format(passages, content);
      } else {
        return null;
      }
    }
  }
}
