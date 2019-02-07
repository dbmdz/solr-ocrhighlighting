package org.mdz.search.solrocr.formats;

import java.util.Arrays;
import org.apache.lucene.search.uhighlight.Passage;
import org.apache.lucene.search.uhighlight.PassageFormatter;
import org.mdz.search.solrocr.util.IterableCharSequence;

/**
 * Takes care of formatting fragments of the OCR format into {@link OcrSnippet} instances.
 */
public abstract class OcrPassageFormatter extends PassageFormatter {

  /**
   * Format the passages that point to subsequences of the document text into {@link OcrSnippet} instances
   *
   * @param passages in the the document text that contain highlighted text
   * @param content of the OCR field, implemented as an {@link IterableCharSequence}
   * @return the parsed snippet representation of the passages
   */
  public abstract OcrSnippet[] format(Passage[] passages, IterableCharSequence content);

  /**
   * Convenience implementation to format document text that is available as a {@link String}.
   *
   * Wraps the {@link String} in a {@link IterableCharSequence} implementation and calls
   * {@link #format(Passage[], IterableCharSequence)}
   *
   * @param passages in the the document text that contain highlighted text
   * @param content of the OCR field, implemented as an {@link IterableCharSequence}
   * @return the parsed snippet representation of the passages
   */
  @Override
  public Object format(Passage[] passages, String content) {
    OcrSnippet[] snips = this.format(passages, IterableCharSequence.fromString(content));
    return Arrays.stream(snips).map(OcrSnippet::getText).toArray(String[]::new);
  }
}
