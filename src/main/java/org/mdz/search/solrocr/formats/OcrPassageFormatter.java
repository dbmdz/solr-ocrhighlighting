package org.mdz.search.solrocr.formats;

import com.google.common.collect.ImmutableSet;
import com.google.common.io.CharStreams;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.apache.commons.text.StringEscapeUtils;
import org.apache.lucene.analysis.charfilter.HTMLStripCharFilter;
import org.apache.lucene.search.uhighlight.Passage;
import org.apache.lucene.search.uhighlight.PassageFormatter;
import org.mdz.search.solrocr.util.IterableCharSequence;
import org.mdz.search.solrocr.util.OcrBox;

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

  /** Merge overlapping matches. **/
  private List<PassageMatch> mergeMatches(int numMatches, int[] matchStarts, int[] matchEnds) {
    Deque<PassageMatch> sortedMatches = IntStream.range(0, numMatches)
        .mapToObj(idx -> new PassageMatch(matchStarts[idx], matchEnds[idx]))
        .collect(Collectors.toCollection(ArrayDeque::new));
    Deque<PassageMatch> mergedMatches = new ArrayDeque<>();
    mergedMatches.add(sortedMatches.removeFirst());
    while (!sortedMatches.isEmpty()) {
      PassageMatch candidate = sortedMatches.removeFirst();
      if (mergedMatches.peekLast().overlaps(candidate)) {
        mergedMatches.peekLast().merge(candidate);
      } else {
        mergedMatches.add(candidate);
      }
    }
    return new ArrayList<>(mergedMatches);
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
      if (passage.getNumMatches() > 0) {
        List<PassageMatch> matches = mergeMatches(passage.getNumMatches(), passage.getMatchStarts(), passage.getMatchEnds());
        for (PassageMatch match : matches) {
          int matchStart = content.subSequence(passage.getStartOffset(), match.start).toString().length();
          sb.insert(extraChars + matchStart, startHlTag);
          extraChars += startHlTag.length();
          int matchEnd = content.subSequence(passage.getStartOffset(), match.end).toString().length();
          sb.insert(Math.min(extraChars + matchEnd, sb.length()), endHlTag);
          extraChars += endHlTag.length();
        }
      }
      String fullFragment = sb.toString();
      String xmlFragment = truncateFragment(fullFragment);
      int adjustedStart = passage.getStartOffset() + fullFragment.indexOf(xmlFragment);
      String pageId = determinePage(xmlFragment, adjustedStart, content);
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
  public abstract String determinePage(String ocrFragment, int startOffset, IterableCharSequence content);

  /** Truncate an OCR fragment to remove undesired parts, most often from the front or end. */
  protected String truncateFragment(String ocrFragment) {
    // Default: No-Op implementation
    return ocrFragment;
  }

  /** Parse an {@link OcrSnippet} from an OCR fragment. */
  protected abstract OcrSnippet parseFragment(String ocrFragment, String pageId);

  /** Merge adjacent OCR boxes into a single one, taking line breaks into account **/
  protected List<OcrBox> mergeBoxes(List<OcrBox> boxes) {
    List<OcrBox> out = new ArrayList<>();
    Iterator<OcrBox> it = boxes.iterator();
    OcrBox curBox = it.next();
    StringBuilder curText = new StringBuilder(curBox.text);
    while (it.hasNext()) {
      OcrBox nextBox = it.next();
      float xDiff = nextBox.lrx - curBox.lrx;
      if (xDiff < 0) {  // New line?
        curBox.text = curText.toString();
        out.add(curBox);
        curBox = nextBox;
        curText = new StringBuilder(curBox.text);
        continue;
      }
      curText.append(" ");
      curText.append(nextBox.text);
      if (nextBox.lrx > curBox.lrx) {
        curBox.lrx = nextBox.lrx;
      }
      if (nextBox.lry > curBox.lry) {
        curBox.lry = nextBox.lry;
      }
      if (nextBox.uly < curBox.uly) {
        curBox.uly = nextBox.uly;
      }
    }
    out.add(curBox);
    return out;
  }

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

  private static class PassageMatch {
    public int start;
    public int end;

    public PassageMatch(int start, int end) {
      this.start = start;
      this.end = end;
    }

    public boolean overlaps(PassageMatch other) {
      int s1 = this.start;
      int e1 = this.end;
      int s2 = other.start;
      int e2 = other.end;
      return (s1 <= s2 && s2 <= e1) ||  //   --------
                                        // -----

             (s1 <= e2 && e2 <= e1) ||  // --------
                                        //      -----

             (s2 <= s1 && s1 <= e2 &&   // --------
              s2 <= e1 && e1 <= e2);    //   ---
    }

    public void merge(PassageMatch other) {
      if (this.end < other.end) {
        this.end = other.end;
      } else if (this.start > other.start) {
        this.start = other.start;
      }
    }

    @Override
    public String toString() {
      return String.format("PassageMatch{start=%d, end=%d}", start, end);
    }
  }
}
