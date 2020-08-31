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
import java.util.Collection;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
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
    TreeMap<Integer, OcrPage> pages = this.parsePages(ocrFragment);
    List<OcrBox> allBoxes = this.parseWords(ocrFragment, pages, page.id);
    if (allBoxes.isEmpty()) {
      return null;
    }

    // Grouped by columns
    List<List<OcrBox>> byColumns = new ArrayList<>();
    List<OcrBox> currentCol = new ArrayList<>();
    OcrBox prevBox = null;
    String pageId = null;
    for (OcrBox box : allBoxes) {
      // Stupid, haphazard heuristic for column detection: If the next box is at least the height of the current box
      // times five higher on the page, we're on a new column. Or if the page changes.
      // FIXME: This cleary needs some more thought put into it
      boolean newColumn = prevBox != null && (box.getUly() + prevBox.getHeight() * 5) < prevBox.getUly();
      boolean newPage = pageId != null && !box.getPageId().equals(pageId);
      if (newColumn || newPage) {
        byColumns.add(currentCol);
        currentCol = new ArrayList<>();
      }
      currentCol.add(box);
      // Skip very low-height boxes since they throw off the heuristic, we still track page changes, though!
      if (box.getHeight() > 5) {
        prevBox = box;
      }
      pageId = box.getPageId();
    }
    byColumns.add(currentCol);

    // Get highlighted spans
    List<List<OcrBox>> hlSpans = new ArrayList<>();
    List<OcrBox> currentSpan = null;
    for (OcrBox wordBox : allBoxes) {
      if (wordBox.isInHighlight()) {
        if (currentSpan == null) {
          currentSpan = new ArrayList<>();
        }
        currentSpan.add(wordBox);
      } else if (currentSpan != null) {
        hlSpans.add(currentSpan);
        currentSpan = null;
      }
    }
    if (currentSpan != null) {
      hlSpans.add(currentSpan);
    }

    String highlightedText = getTextFromXml(ocrFragment);
    List<OcrBox> snippetRegions = byColumns.stream()
        .map(this::determineSnippetRegion)
        .filter(r -> !r.getText().isEmpty() && !r.getText().trim().isEmpty())
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

    OcrSnippet snip = new OcrSnippet(highlightedText, snippetPages, snippetRegions);
    this.addHighlightsToSnippet(hlSpans, snip);
    return snip;
  }

  private OcrBox determineSnippetRegion(List<OcrBox> wordBoxes) {
    float snipUlx = wordBoxes.stream().map(OcrBox::getUlx).min(Float::compareTo).get();
    float snipUly = wordBoxes.stream().map(OcrBox::getUly).min(Float::compareTo).get();
    float snipLrx = wordBoxes.stream().map(OcrBox::getLrx).max(Float::compareTo).get();
    float snipLry = wordBoxes.stream().map(OcrBox::getLry).max(Float::compareTo).get();
    String pageId = wordBoxes.get(0).getPageId();

    String regionText = wordBoxes.stream()
        .filter(box -> !box.isHyphenated() || box.getHyphenStart())
        .map(box ->   box.isHyphenated() ? box.getDehyphenatedForm() : box.getText())
        .collect(Collectors.joining(" "));
    OcrBox firstBox = wordBoxes.get(0);
    OcrBox lastBox = wordBoxes.get(wordBoxes.size() - 1);
    if (firstBox.isInHighlight() && !firstBox.getText().contains(startHlTag)) {
      regionText = startHlTag + regionText;
    }
    if (lastBox.isInHighlight() && !lastBox.getText().contains(endHlTag)) {
      regionText = regionText + endHlTag;
    }

    return new OcrBox(regionText, pageId, snipUlx, snipUly, snipLrx, snipLry, false);
  }

  /** Parse word boxes from an OCR fragment. */
  protected abstract List<OcrBox> parseWords(String ocrFragment, TreeMap<Integer, OcrPage> pages, String startPage);

  /** Parse pages and their offsets from an OCR fragment.
   *
   * The type needs to be a TreeMap, since the downstream tasks need to find keys close to each other,
   * which is much more efficient with this type.
   */
  protected abstract TreeMap<Integer, OcrPage> parsePages(String ocrFragment);

  protected void addHighlightsToSnippet(List<List<OcrBox>> hlSpans, OcrSnippet snippet) {
    hlSpans.stream().flatMap(Collection::stream)
        .forEach(box -> {
          Optional<OcrBox> region = snippet.getSnippetRegions().stream().filter(r -> r.contains(box)).findFirst();
          if (!region.isPresent()) {
            return;
          }
          if (!this.absoluteHighlights) {
            float xOffset = region.get().getUlx();
            float yOffset = region.get().getUly();
            box.setUlx(box.getUlx() - xOffset);
            box.setLrx(box.getLrx() - xOffset);
            box.setUly(box.getUly() - yOffset);
            box.setLry(box.getLry() - yOffset);
          }
          box.setParentRegionIdx(snippet.getSnippetRegions().indexOf(region.get()));
          // Remove the highlighting tags from the text
          box.setText(box.getText().replaceAll(startHlTag, "").replaceAll(endHlTag, ""));
        });
    hlSpans.forEach(span -> snippet.addHighlightSpan(this.mergeBoxes(span)));
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
      boolean newLine = yDiff > (0.75 * lineHeight);
      boolean newPage = !StringUtils.equals(nextBox.getPageId(), curBox.getPageId());
      if (newLine || newPage) {
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
    // Clear the page id to keep the response slim, the user can determine it from the associated region
    curBox.setPageId(null);
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
