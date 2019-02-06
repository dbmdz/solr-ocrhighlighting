package org.mdz.search.solrocr.formats;

import java.text.BreakIterator;

public interface OcrFormat {

  /** Set the block type to break the input on and the number of blocks that should form a passage */
  void setBreakParameters(OcrBlock breakBlock, int contextSize);

  /** Get a BreakIterator that splits the content according to the break parameters */
  BreakIterator getBreakIterator();

  /** Get a PassageFormatter that builds OCR snippets from passages */
  OcrPassageFormatter getPassageFormatter(OcrBlock limitBlock, String prehHighlightTag, String postHighlightTag);
}
