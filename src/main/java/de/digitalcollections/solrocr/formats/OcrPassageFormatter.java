package de.digitalcollections.solrocr.formats;

import com.google.common.collect.ImmutableSet;
import de.digitalcollections.solrocr.iter.IterableCharSequence;
import de.digitalcollections.solrocr.model.OcrBox;
import de.digitalcollections.solrocr.model.OcrPage;
import de.digitalcollections.solrocr.model.OcrSnippet;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.apache.commons.io.IOUtils;
import org.apache.commons.text.StringEscapeUtils;
import org.apache.lucene.analysis.charfilter.HTMLStripCharFilter;
import org.apache.lucene.search.uhighlight.Passage;
import org.apache.lucene.search.uhighlight.PassageFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Takes care of formatting fragments of the OCR format into {@link OcrSnippet} instances.
 */
public abstract class OcrPassageFormatter extends PassageFormatter {
  private static final Pattern LAST_INNER_TAG_PAT = Pattern.compile("[a-zA-Z0-9]</");
  private static final Pattern TITLE_PAT = Pattern.compile("<title>.*?</title>");

  private static final Logger logger = LoggerFactory.getLogger(OcrPassageFormatter.class);
  protected final String startHlTag;
  protected final String endHlTag;
  protected final boolean absoluteHighlights;


  protected OcrPassageFormatter(String startHlTag, String endHlTag, boolean absoluteHighlights) {
    this.startHlTag = startHlTag;
    this.endHlTag = endHlTag;
    this.absoluteHighlights = absoluteHighlights;
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
      if (!mergedMatches.isEmpty() && mergedMatches.peekLast().overlaps(candidate)) {
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
      try {
        snippets[i] = format(passage, content);
      } catch (IndexOutOfBoundsException e) {
        String errorMsg = String.format(
            "Could not create snippet (start=%d, end=%d) from content at '%s' due to an out-of-bounds error.\n"
          + "\nDoes the file on disk correspond to the document that was used during indexing?",
            passage.getStartOffset(), passage.getEndOffset(), content.getIdentifier());
        logger.error(errorMsg, e);
      }
    }
    return snippets;
  }

  private OcrSnippet format(Passage passage, IterableCharSequence content) {
    StringBuilder sb = new StringBuilder(content.subSequence(passage.getStartOffset(), passage.getEndOffset()));
    int extraChars = 0;
    if (passage.getNumMatches() > 0) {
      List<PassageMatch> matches = mergeMatches(passage.getNumMatches(), passage.getMatchStarts(), passage.getMatchEnds());
      for (PassageMatch match : matches) {
        int matchStart = content.subSequence(passage.getStartOffset(), match.start).toString().length();
        sb.insert(extraChars + matchStart, startHlTag);
        extraChars += startHlTag.length();
        int matchEnd = content.subSequence(passage.getStartOffset(), match.end).toString().length();
        String matchText = sb.substring(extraChars + matchStart, extraChars + matchEnd);
        if (matchText.trim().endsWith(">")) {
          // Set the end of the match to the position before the last inner closing tag inside of the match.
          Matcher m = LAST_INNER_TAG_PAT.matcher(matchText);
          int idx = -1;
          while (m.find()) {
            idx = m.start() + 1;
          }
          if (idx > -1) {
            matchEnd -= (matchText.length() - idx);
          }
        }
        sb.insert(Math.min(extraChars + matchEnd, sb.length()), endHlTag);
        extraChars += endHlTag.length();
      }
    }
    String xmlFragment = sb.toString();
    OcrPage page = determineStartPage(xmlFragment, passage.getStartOffset(), content);
    OcrSnippet snip = parseFragment(xmlFragment, page);
    if (snip != null) {
      snip.setScore(passage.getScore());
    }
    return snip;
  }

  /** Helper method to get plaintext from XML/HTML-like fragments */
  protected String getTextFromXml(String xmlFragment) {
    HTMLStripCharFilter filter = new HTMLStripCharFilter(
        new StringReader(TITLE_PAT.matcher(xmlFragment).replaceAll("")),
        ImmutableSet.of(startHlTag.substring(1, startHlTag.length() - 1)));
    try {
      String text = IOUtils.toString(filter);
      return StringEscapeUtils.unescapeXml(text)
          .replaceAll("\n", "")
          .replaceAll("\\s+", " ")
          .trim();
    } catch (IOException e) {
      return xmlFragment;
    }
  }

  /** Determine the page an OCR fragment resides on. */
  public abstract OcrPage determineStartPage(String ocrFragment, int startOffset, IterableCharSequence content);

  /** Parse an {@link OcrSnippet} from an OCR fragment. */
  protected OcrSnippet parseFragment(String ocrFragment, OcrPage page) {
    List<List<OcrBox>> hlBoxes = new ArrayList<>();
    TreeMap<Integer, OcrPage> pages = this.parsePages(ocrFragment);
    List<OcrBox> allBoxes = this.parseWords(ocrFragment, pages, page.id);
    if (allBoxes.isEmpty()) {
      return null;
    }

    Map<String, List<OcrBox>> grouped = allBoxes.stream().collect(Collectors.groupingBy(
        OcrBox::getPageId, LinkedHashMap::new, Collectors.toList()));

    // Get highlighted spans
    List<OcrBox> currentHl = null;
    for (OcrBox wordBox : allBoxes) {
      if (wordBox.isHighlight()) {
        if (currentHl == null) {
          currentHl = new ArrayList<>();
        }
        currentHl.add(wordBox);
      } else if (currentHl != null) {
        hlBoxes.add(currentHl);
        currentHl = null;
      }
    }
    if (currentHl != null) {
      hlBoxes.add(currentHl);
    }

    List<OcrBox> snippetRegions = grouped.entrySet().stream()
        .map(e -> determineSnippetRegion(e.getValue(), e.getKey()))
        .collect(Collectors.toList());
    Set<String> snippetPageIds = snippetRegions.stream()
        .map(OcrBox::getPageId).collect(Collectors.toSet());
    List<OcrPage> allPages = new ArrayList<>();
    allPages.add(page);
    allPages.addAll(pages.values());
    List<OcrPage> snippetPages = allPages.stream()
        .filter(p -> snippetPageIds.contains(p.id))
        .distinct()
        .collect(Collectors.toList());

    OcrSnippet snip = new OcrSnippet(getTextFromXml(ocrFragment), snippetPages, snippetRegions);
    this.addHighlightsToSnippet(hlBoxes, snip);
    return snip;
  }

  private OcrBox determineSnippetRegion(List<OcrBox> wordBoxes, String pageId) {
    float snipUlx = wordBoxes.stream().map(OcrBox::getUlx).min(Float::compareTo).get();
    float snipUly = wordBoxes.stream().map(OcrBox::getUly).min(Float::compareTo).get();
    float snipLrx = wordBoxes.stream().map(OcrBox::getLrx).max(Float::compareTo).get();
    float snipLry = wordBoxes.stream().map(OcrBox::getLry).max(Float::compareTo).get();
    return new OcrBox(null, pageId, snipUlx, snipUly, snipLrx, snipLry, false);
  }


  /** Parse word boxes from an OCR fragment. */
  protected abstract List<OcrBox> parseWords(String ocrFragment, TreeMap<Integer, OcrPage> pages, String startPage);

  /** Parse pages and their offsets from an OCR fragment.
   *
   * The type needs to be a TreeMap, since the downstream tasks need to find keys close to each other,
   * which is much more efficient with this type.
   */
  protected abstract TreeMap<Integer, OcrPage> parsePages(String ocrFragment);

  protected void addHighlightsToSnippet(List<List<OcrBox>> hlBoxes, OcrSnippet snippet) {
    for (OcrBox region : snippet.getSnippetRegions()) {
      final float xOffset = this.absoluteHighlights ? 0 : region.getUlx();
      final float yOffset = this.absoluteHighlights ? 0 : region.getUly();
      hlBoxes.stream()
          .map(bs -> bs.stream()
              .filter(b -> b.getPageId().equals(region.getPageId()))
              .map(b -> new OcrBox(b.getText(), b.getPageId(), b.getUlx() - xOffset, b.getUly() - yOffset,
                                   b.getLrx() - xOffset, b.getLry() - yOffset, b.isHighlight()))
              .collect(Collectors.toList()))
          .forEach(bs -> snippet.addHighlightRegion(this.mergeBoxes(bs)));
    }
  }


  /** Merge adjacent OCR boxes into a single one, taking line breaks into account **/
  protected List<OcrBox> mergeBoxes(List<OcrBox> boxes) {
    if (boxes.size() < 2) {
      return boxes;
    }
    List<OcrBox> out = new ArrayList<>();
    Iterator<OcrBox> it = boxes.iterator();
    OcrBox curBox = it.next();
    StringBuilder curText = new StringBuilder(curBox.getText());
    // Combine word boxes into a single new OCR box until we hit a linebreak
    while (it.hasNext()) {
      OcrBox nextBox = it.next();
      // We consider a box on a new line if its vertical distance from the current box is close to the line height
      float lineHeight = curBox.getLry() - curBox.getUly();
      float yDiff = Math.abs(nextBox.getUly() - curBox.getUly());
      if (yDiff > (0.75 * lineHeight)) {
        curBox.setText(curText.toString());
        out.add(curBox);
        curBox = nextBox;
        curText = new StringBuilder(curBox.getText());
        continue;
      }
      curText.append(" ");
      curText.append(nextBox.getText());
      if (nextBox.getLrx() > curBox.getLrx()) {
        curBox.setLrx(nextBox.getLrx());
      }
      if (nextBox.getLry() > curBox.getLry()) {
        curBox.setLry(nextBox.getLry());
      }
      if (nextBox.getUly() < curBox.getUly()) {
        curBox.setUly(nextBox.getUly());
      }
    }
    curBox.setText(curText.toString());
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
