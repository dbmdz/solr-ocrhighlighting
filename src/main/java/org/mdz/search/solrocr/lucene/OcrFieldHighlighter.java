package org.mdz.search.solrocr.lucene;

import java.io.IOException;
import java.text.BreakIterator;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.search.uhighlight.AnalysisOffsetStrategy;
import org.apache.lucene.search.uhighlight.FieldHighlighter;
import org.apache.lucene.search.uhighlight.FieldOffsetStrategy;
import org.apache.lucene.search.uhighlight.OffsetsEnum;
import org.apache.lucene.search.uhighlight.Passage;
import org.apache.lucene.search.uhighlight.PassageFormatter;
import org.apache.lucene.search.uhighlight.PassageScorer;
import org.mdz.search.solrocr.formats.OcrPassageFormatter;
import org.mdz.search.solrocr.formats.OcrSnippet;
import org.mdz.search.solrocr.util.IterableCharSequence;

public class OcrFieldHighlighter extends FieldHighlighter {
  public OcrFieldHighlighter(String field, FieldOffsetStrategy fieldOffsetStrategy, PassageScorer passageScorer,
                             BreakIterator breakIter, OcrPassageFormatter formatter, int maxPassages,
                             int maxNoHighlightPassages) {
    super(field, fieldOffsetStrategy, breakIter, passageScorer, maxPassages, maxNoHighlightPassages,
          (PassageFormatter) formatter);
    if (fieldOffsetStrategy instanceof AnalysisOffsetStrategy) {
      throw new RuntimeException("AnalysisOffsetStrategy is not supported for OCR fields.");
    }
  }

  /**
   * The primary method -- highlight this doc, assuming a specific field and given this content.
   */
  public OcrSnippet[] highlightFieldForDoc(LeafReader reader, int docId, IterableCharSequence content) throws IOException {
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
