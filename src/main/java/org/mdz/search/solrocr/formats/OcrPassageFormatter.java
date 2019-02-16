package org.mdz.search.solrocr.formats;

import com.google.common.collect.ImmutableSet;
import com.google.common.io.CharStreams;
import java.io.IOException;
import java.io.StringReader;
import java.util.Arrays;
import org.apache.commons.text.StringEscapeUtils;
import org.apache.lucene.analysis.charfilter.HTMLStripCharFilter;
import org.apache.lucene.search.uhighlight.Passage;
import org.apache.lucene.search.uhighlight.PassageFormatter;
import org.mdz.search.solrocr.util.IterableCharSequence;

/**
 * Takes care of formatting fragments of the OCR format into {@link OcrSnippet} instances.
 */
public abstract class OcrPassageFormatter extends PassageFormatter {
  protected final String startHlTag;
  protected final String endHlTag;

  protected OcrPassageFormatter(String startHlTag, String endHlTag) {
    this.startHlTag = startHlTag;
    this.endHlTag = endHlTag;
  }

  /**
   * Format the passages that point to subsequences of the document text into {@link OcrSnippet} instances
   *
   * @param passages in the the document text that contain highlighted text
   * @param content of the OCR field, implemented as an {@link IterableCharSequence}
   * @return the parsed snippet representation of the passages
   */
  public OcrSnippet[] format(Passage[] passages, IterableCharSequence content) {
    OcrSnippet[] snippets = new OcrSnippet[passages.length];
    for (int i=0; i < passages.length; i++) {
      Passage passage = passages[i];
      StringBuilder sb = new StringBuilder(content.subSequence(passage.getStartOffset(), passage.getEndOffset()));
      int extraChars = 0;
      for (int j=0; j < passage.getNumMatches(); j++) {
        int matchStart = content.subSequence(passage.getStartOffset(), passage.getMatchStarts()[j]).toString().length();
        sb.insert(extraChars + matchStart, startHlTag);
        extraChars += startHlTag.length();
        int matchEnd = content.subSequence(passage.getStartOffset(), passage.getMatchEnds()[j]).toString().length();
        sb.insert(extraChars + matchEnd, endHlTag);
        extraChars += endHlTag.length();
      }
      String xmlFragment = truncateFragment(sb.toString());
      String pageId = determinePage(xmlFragment, passage.getStartOffset(), content);
      snippets[i] = parseFragment(xmlFragment, pageId);
      snippets[i].setScore(passage.getScore());
    }
    return snippets;
  }

  /** Helper method to get plaintext from XML/HTML-like fragments */
  protected String getTextFromXml(String xmlFragment) {
    HTMLStripCharFilter filter = new HTMLStripCharFilter(
        new StringReader(xmlFragment),
        ImmutableSet.of(startHlTag.substring(1, startHlTag.length() - 1)));
    try {
      String text = CharStreams.toString(filter);
      return StringEscapeUtils.unescapeXml(text)
          .replaceAll("\n", "")
          .replaceAll("\\s+", " ")
          .trim();
    } catch (IOException e) {
      return xmlFragment;
    }
  }

  /** Determine the id of the page an OCR fragment resides on. */
  protected abstract String determinePage(String ocrFragment, int startOffset, IterableCharSequence content);

  /** Truncate an OCR fragment to remove undesired parts, most often from the front or end. */
  protected String truncateFragment(String ocrFragment) {
    // Default: No-Op implementation
    return ocrFragment;
  }

  /** Parse an {@link OcrSnippet} from an OCR fragment. */
  protected abstract OcrSnippet parseFragment(String ocrFragment, String pageId);

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
