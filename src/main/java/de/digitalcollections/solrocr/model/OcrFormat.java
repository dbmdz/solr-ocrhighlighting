package de.digitalcollections.solrocr.model;

import de.digitalcollections.solrocr.formats.OcrPassageFormatter;
import java.io.Reader;
import java.text.BreakIterator;

/**
 * Provides access to format-specific {@link BreakIterator} and {@link OcrPassageFormatter} instances.
 */
public interface OcrFormat {
  /** Get a BreakIterator that splits the content according to the break parameters
   *
   * @param breakBlock the type of {@link OcrBlock} that the input document is split on to build passages
   * @param limitBlock the type of {@link OcrBlock} that a passage may not cross
   * @param contextSize the number of break blocks in a context that forms a highlighting passage
   * */
  BreakIterator getBreakIterator(OcrBlock breakBlock, OcrBlock limitBlock, int contextSize);

  /**
   * Get a PassageFormatter that builds OCR snippets from passages
   *
   * @param prehHighlightTag the tag to put in the snippet text before a highlighted region, e.g. &lt;em&gt;
   * @param postHighlightTag the tag to put in the snippet text after a highlighted region, e.g. &lt;/em&gt;
   * @param absoluteHighlights whether the coordinates for highlights should be absolute, i.e. relative to the page
   *                           and not the containing snippet
   * @param alignSpans whether the spans in the text and image should match precisely. If false, the text spans will
   *                   be more precise than the image "spans", since the latter are restricted to the granularity of
   *                   the OCR document.
   */
  OcrPassageFormatter getPassageFormatter(
      String prehHighlightTag, String postHighlightTag,  boolean absoluteHighlights, boolean alignSpans);

  Reader filter(Reader input);

  /**
   * Check if the string chunk contains data formatted according to the implementing format.
   *
   * @param ocrChunk a chunk of a file's content
   * @return whether the chunk is formatted according to the implementing format.
   */
  boolean hasFormat(String ocrChunk);
}
