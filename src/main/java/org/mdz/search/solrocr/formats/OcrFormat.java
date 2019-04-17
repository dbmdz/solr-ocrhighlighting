package org.mdz.search.solrocr.formats;

import java.text.BreakIterator;

/**
 * Provides access to format-specific {@link BreakIterator} and {@link OcrPassageFormatter} instances.
 */
public interface OcrFormat {

  /** Set the block type to break the input on and the number of blocks that should form a passage
   *
   * This is decoupled from {@link #getBreakIterator()} since the {@link OcrPassageFormatter} instance is likely
   * to reuse these parameters.
   *
   * FIXME: This is not really elegant, find a way to not depend on this
   *
   * @param breakBlock the type of {@link OcrBlock} that the input document is split on to build passages
   * @param contextSize the number of break blocks in a context that forms a highlighting passage
   */
  void setBreakParameters(OcrBlock breakBlock, int contextSize);

  /** Get a BreakIterator that splits the content according to the break parameters */
  BreakIterator getBreakIterator();

  /**
   * Get a PassageFormatter that builds OCR snippets from passages
   *
   * @param limitBlock the type of {@link OcrBlock} that a passage should not go beyond. This is most likely going to
   *                   be "PAGE" , "PARAGRAPH" or "BLOCK", since these often delimit unrelated units of text.
   * @param prehHighlightTag the tag to put in the snippet text before a highlighted region, e.g. &lt;em&gt;
   * @param postHighlightTag the tag to put in the snippet text after a highlighted region, e.g. &lt;/em&gt;
   */
  OcrPassageFormatter getPassageFormatter(OcrBlock limitBlock, String prehHighlightTag, String postHighlightTag,
                                          boolean absoluteHighlights);
}
