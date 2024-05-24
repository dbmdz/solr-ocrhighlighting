package com.github.dbmdz.solrocr.lucene;

import static com.github.dbmdz.solrocr.formats.OcrParser.END_HL;
import static com.github.dbmdz.solrocr.formats.OcrParser.START_HL;

import com.github.dbmdz.solrocr.formats.OcrParser;
import com.github.dbmdz.solrocr.iter.BreakLocator;
import com.github.dbmdz.solrocr.lucene.filters.SanitizingXmlFilter;
import com.github.dbmdz.solrocr.model.OcrBlock;
import com.github.dbmdz.solrocr.model.OcrBox;
import com.github.dbmdz.solrocr.model.OcrFormat;
import com.github.dbmdz.solrocr.model.OcrPage;
import com.github.dbmdz.solrocr.model.OcrSnippet;
import com.github.dbmdz.solrocr.reader.SourceReader;
import com.github.dbmdz.solrocr.reader.StringSourceReader;
import com.google.common.collect.Lists;
import com.google.common.collect.Range;
import java.io.StringReader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.apache.lucene.search.uhighlight.Passage;
import org.apache.lucene.search.uhighlight.PassageFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Takes care of formatting fragments of the OCR format into {@link OcrSnippet} instances. */
public class OcrPassageFormatter extends PassageFormatter {
  protected static final Pattern LAST_INNER_TAG_PAT = Pattern.compile("[a-zA-Z0-9]</");

  private static final Logger logger = LoggerFactory.getLogger(OcrPassageFormatter.class);

  private final OcrFormat format;
  protected final String startHlTag;
  protected final String endHlTag;
  protected final boolean absoluteHighlights;
  protected final boolean alignSpans;
  protected final boolean trackPages;

  public OcrPassageFormatter(
      String startHlTag,
      String endHlTag,
      boolean absoluteHighlights,
      boolean alignSpans,
      boolean trackPages,
      OcrFormat format) {
    this.startHlTag = startHlTag;
    this.endHlTag = endHlTag;
    this.absoluteHighlights = absoluteHighlights;
    this.alignSpans = alignSpans;
    this.trackPages = trackPages;
    this.format = format;
  }

  /** Merge overlapping matches. * */
  protected List<PassageMatch> mergeMatches(int numMatches, int[] matchStarts, int[] matchEnds) {
    Deque<PassageMatch> sortedMatches =
        IntStream.range(0, numMatches)
            .mapToObj(idx -> new PassageMatch(matchStarts[idx], matchEnds[idx]))
            .collect(Collectors.toCollection(ArrayDeque::new));
    Deque<PassageMatch> mergedMatches = new ArrayDeque<>();
    mergedMatches.add(sortedMatches.removeFirst());
    while (!sortedMatches.isEmpty()) {
      PassageMatch candidate = sortedMatches.removeFirst();
      if (!mergedMatches.isEmpty() && mergedMatches.peekLast().overlaps(candidate)) {
        // Cannot be null due to isEmpty check, and no concurrent accesses that could
        // remove it
        mergedMatches.peekLast().merge(candidate);
      } else {
        mergedMatches.add(candidate);
      }
    }
    return new ArrayList<>(mergedMatches);
  }

  /**
   * Format the passages that point to subsequences of the document text into {@link OcrSnippet}
   * instances
   *
   * @param passages in the the document text that contain highlighted text
   * @param content of the OCR field, implemented as an {@link SourceReader}
   * @return the parsed snippet representation of the passages
   */
  public OcrSnippet[] format(Passage[] passages, SourceReader content) {
    OcrSnippet[] snippets = new OcrSnippet[passages.length];
    for (int i = 0; i < passages.length; i++) {
      Passage passage = passages[i];
      try {
        snippets[i] = format(passage, content);
      } catch (IndexOutOfBoundsException e) {
        String errorMsg =
            String.format(
                Locale.US,
                "Could not create snippet (start=%d, end=%d) from content at '%s' due to an out-of-bounds error.\n"
                    + "\nDoes the file on disk correspond to the document that was used during indexing?",
                passage.getStartOffset(),
                passage.getEndOffset(),
                content.getIdentifier());
        logger.error(errorMsg, e);
      }
    }
    return snippets;
  }

  protected String getHighlightedFragment(Passage passage, SourceReader content) {
    StringBuilder sb =
        new StringBuilder(content.readUtf8String(passage.getStartOffset(), passage.getLength()));
    int extraChars = 0;
    if (passage.getNumMatches() > 0) {
      List<PassageMatch> matches =
          mergeMatches(passage.getNumMatches(), passage.getMatchStarts(), passage.getMatchEnds());
      for (PassageMatch match : matches) {
        // Can't just do match.start - passage.getStartOffset(), since both offsets are relative to
        // **UTF-8 bytes**, but we need **UTF-16 codepoint** offsets in the code.
        String preMatchContent =
            content.readUtf8String(
                passage.getStartOffset(), match.start - passage.getStartOffset());
        int matchStart = preMatchContent.length();
        if (alignSpans) {
          matchStart = format.getLastContentStartIdx(preMatchContent);
        }
        sb.insert(
            this.adjustPositionToCharacterEntities(sb.toString(), extraChars + matchStart),
            START_HL);
        extraChars += START_HL.length();
        // Again, can't just do match.end - passage.getStartOffset(), since we need char offsets
        // (see above).
        int matchEnd =
            content
                .readUtf8String(passage.getStartOffset(), match.end - passage.getStartOffset())
                .length();
        String matchText = sb.substring(extraChars + matchStart, extraChars + matchEnd);
        if (matchText.trim().endsWith(">")) {
          // Set the end of the match to the position before the last inner closing tag inside of
          // the match. This is only relevant for hOCR at the moment
          Matcher m = LAST_INNER_TAG_PAT.matcher(matchText);
          int idx = -1;
          while (m.find()) {
            idx = m.start() + 1;
          }
          if (idx > -1) {
            matchEnd -= (matchText.length() - idx);
          }
        }
        matchEnd = Math.min(matchEnd + extraChars, sb.length());
        if (alignSpans && matchEnd != sb.length()) {
          String postMatchContent = sb.substring(matchEnd, sb.length());
          matchEnd += format.getFirstContentEndIdx(postMatchContent);
        }
        sb.insert(this.adjustPositionToCharacterEntities(sb.toString(), matchEnd), END_HL);
        extraChars += END_HL.length();
      }
    }
    return sb.toString();
  }

  /**
   * Adjust the given position within the OCR fragment to account for XML character entities in the
   * OCR word, assumes that the position is within an OCR word.
   *
   * <p>This is necessary since doing this at indexing time would be extremely costly, given that it
   * would need to be run for every single word. At highlighting time it only needs to be run for
   * words that have a highlighting marker inside, since the difference is otherwise not
   * problematic.
   */
  private int adjustPositionToCharacterEntities(String fragment, int position) {
    Range<Integer> wordRange = this.format.getContainingWordLimits(fragment, position);
    int idx = wordRange.lowerEndpoint();
    while (idx >= wordRange.lowerEndpoint() && idx < position) {
      int entStart = fragment.indexOf('&', idx);
      if (entStart < 0 || entStart >= position || entStart > wordRange.upperEndpoint()) {
        // No entities opened before position in the word, start doesn't need to be adjusted
        break;
      }
      int entEnd = fragment.indexOf(';', entStart);
      int entLength = entEnd - entStart;
      // This assumes that the entity decodes to a codepoint that is only one character wide in
      // UTF16, which should be the case for >99.9% of terms people search for...
      position += entLength;
      idx = entEnd + 1;
    }
    return position;
  }

  private OcrSnippet format(Passage passage, SourceReader reader) {
    String xmlFragment = getHighlightedFragment(passage, reader);
    OcrPage initialPage = null;
    if (trackPages) {
      initialPage = determineStartPage(passage.getStartOffset(), reader);
    }
    OcrSnippet snip = parseFragment(xmlFragment, initialPage);
    if (snip != null) {
      snip.setScore(passage.getScore());
    }
    return snip;
  }

  /** Determine the page an OCR fragment resides on. */
  OcrPage determineStartPage(int startOffset, SourceReader reader) {
    BreakLocator pageBreakLocator = this.format.getBreakLocator(reader, OcrBlock.PAGE);
    int pageOffset = pageBreakLocator.preceding(startOffset);
    if (pageOffset == BreakLocator.DONE) {
      // This means the page is, if present, part of the passage, and will be determined during
      // parsing anyway
      return null;
    }
    String pageFragment =
        reader.readUtf8String(pageOffset, Math.min(512, reader.length() - pageOffset));
    return this.format.parsePageFragment(pageFragment);
  }

  /** Parse an {@link OcrSnippet} from an OCR fragment. */
  protected OcrSnippet parseFragment(String ocrFragment, OcrPage page) {
    List<OcrBox> allBoxes = this.parseWords(ocrFragment, page);
    if (allBoxes.isEmpty()) {
      return null;
    }

    // Grouped by columns
    List<List<OcrBox>> byColumns = new ArrayList<>();
    List<OcrBox> currentCol = new ArrayList<>();
    OcrBox prevBox = null;
    String pageId = null;
    for (OcrBox box : allBoxes) {
      // Stupid, haphazard heuristic for column detection: If the next box is at least the height of
      // the current box times five higher on the page, we're on a new column. Or if the page
      // changes.
      // FIXME: This clearly needs some more thought put into it
      boolean newColumn =
          prevBox != null && (box.getUly() + prevBox.getHeight() * 5) < prevBox.getUly();
      String boxPageId = box.getPage() == null ? null : box.getPage().id;
      boolean newPage = pageId != null && !pageId.equals(boxPageId);
      if (newColumn || newPage) {
        byColumns.add(currentCol);
        currentCol = new ArrayList<>();
      }
      currentCol.add(box);
      // Skip very low-height boxes since they throw off the heuristic, we still track page changes,
      // though!
      if (box.getHeight() > 5) {
        prevBox = box;
      }
      pageId = boxPageId;
    }
    byColumns.add(currentCol);

    // Get highlighted spans
    Set<OcrPage> pages = new LinkedHashSet<>();
    List<List<OcrBox>> hlSpans = new ArrayList<>();
    List<OcrBox> currentSpan = null;
    for (OcrBox wordBox : allBoxes) {
      if (wordBox.getPage() != null) {
        pages.add(wordBox.getPage());
      }
      if (wordBox.isInHighlight()) {
        boolean isInNewSpan =
            (currentSpan == null
                || currentSpan.isEmpty()
                || !wordBox.getHighlightSpan().equals(currentSpan.get(0).getHighlightSpan()));
        if (isInNewSpan) {
          if (currentSpan != null && !currentSpan.isEmpty()) {
            hlSpans.add(currentSpan);
          }
          currentSpan = new ArrayList<>();
        }
        // Only add the word to the span if some of its text actually is in the highlight span,
        // i.e. don't if the word's text starts with the end-marker.
        if (!wordBox.getText().startsWith(END_HL)) {
          currentSpan.add(wordBox);
        }
      } else if (currentSpan != null && !currentSpan.isEmpty()) {
        hlSpans.add(currentSpan);
        currentSpan = null;
      }
    }
    if (currentSpan != null && !currentSpan.isEmpty()) {
      hlSpans.add(currentSpan);
    }

    String highlightedText =
        OcrParser.boxesToString(allBoxes)
            .replace(START_HL, startHlTag)
            .replace(OcrParser.END_HL, endHlTag);
    List<OcrBox> snippetRegions =
        byColumns.stream()
            .map(this::determineSnippetRegion)
            .filter(r -> !r.getText().isEmpty() && !r.getText().trim().isEmpty())
            .collect(Collectors.toList());
    Set<String> snippetPageIds =
        snippetRegions.stream()
            .filter(b -> b.getPage() != null)
            .map(b -> b.getPage().id)
            .collect(Collectors.toSet());
    List<OcrPage> allPages = new ArrayList<>();
    if (page != null) {
      allPages.add(page);
    }
    allPages.addAll(pages);
    List<OcrPage> snippetPages =
        allPages.stream()
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
    OcrPage page = wordBoxes.get(0).getPage();

    String regionText = OcrParser.boxesToString(wordBoxes);
    OcrBox firstBox = wordBoxes.get(0);
    OcrBox lastBox = wordBoxes.get(wordBoxes.size() - 1);
    if (firstBox.isInHighlight() && !firstBox.getText().contains(START_HL)) {
      regionText = START_HL + regionText;
    }
    if (lastBox.isInHighlight() && !lastBox.getText().contains(END_HL)) {
      regionText = regionText + END_HL;
    }
    regionText = regionText.replace(START_HL, startHlTag).replace(END_HL, endHlTag);

    return new OcrBox(regionText, page, snipUlx, snipUly, snipLrx, snipLry, null);
  }

  /** Parse word boxes from an OCR fragment. */
  protected List<OcrBox> parseWords(String ocrFragment, OcrPage startPage) {
    List<OcrBox> words = new ArrayList<>();
    List<OcrParser.ParsingFeature> parsingFeatures =
        Lists.newArrayList(
            OcrParser.ParsingFeature.TEXT,
            OcrParser.ParsingFeature.COORDINATES,
            OcrParser.ParsingFeature.ALTERNATIVES,
            OcrParser.ParsingFeature.HIGHLIGHTS);
    if (trackPages) {
      parsingFeatures.add(OcrParser.ParsingFeature.PAGES);
    }
    OcrParser parser =
        format.getParser(
            new SanitizingXmlFilter(new StringReader(ocrFragment), true),
            parsingFeatures.toArray(new OcrParser.ParsingFeature[0]));
    boolean onStartPage = true;
    for (OcrBox box : parser) {
      if (onStartPage && box.getPage() == null) {
        box.setPage(startPage);
      } else if (box.getPage() != null) {
        onStartPage = false;
      }
      words.add(box);
    }
    return words;
  }

  protected void addHighlightsToSnippet(List<List<OcrBox>> hlSpans, OcrSnippet snippet) {
    hlSpans.stream()
        .flatMap(Collection::stream)
        .forEach(
            box -> {
              Optional<OcrBox> region =
                  snippet.getSnippetRegions().stream().filter(r -> r.contains(box)).findFirst();
              if (!region.isPresent()) {
                return;
              }
              if (!this.absoluteHighlights) {
                float xOffset = region.get().getUlx();
                float yOffset = region.get().getUly();
                if ((box.getUlx() > 0 && box.getUlx() < 1)
                    || (box.getUly() > 0 && box.getUly() < 1)) {
                  // Relative coordinates, need to do some more calculations
                  float snipWidth = region.get().getLrx() - xOffset;
                  float snipHeight = region.get().getLry() - yOffset;
                  box.setUlx(truncateFloat((box.getUlx() - xOffset) / snipWidth));
                  box.setLrx(truncateFloat((box.getLrx() - xOffset) / snipWidth));
                  box.setUly(truncateFloat((box.getUly() - yOffset) / snipHeight));
                  box.setLry(truncateFloat((box.getLry() - yOffset) / snipHeight));
                } else {
                  box.setUlx(box.getUlx() - xOffset);
                  box.setLrx(box.getLrx() - xOffset);
                  box.setUly(box.getUly() - yOffset);
                  box.setLry(box.getLry() - yOffset);
                }
              }
              box.setParentRegionIdx(snippet.getSnippetRegions().indexOf(region.get()));
              // Remove the highlighting tags from the text
              box.setText(box.getText().replace(START_HL, "").replace(END_HL, ""));
            });
    hlSpans.forEach(span -> snippet.addHighlightSpan(this.mergeBoxes(span)));
  }

  /** Merge adjacent OCR boxes into a single one, taking line breaks into account * */
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
      // We consider a box on a new line if its vertical distance from the current box is close to
      // the line height
      float lineHeight = curBox.getLry() - curBox.getUly();
      float yDiff = Math.abs(nextBox.getUly() - curBox.getUly());
      boolean newLine = yDiff > (0.75 * lineHeight);
      boolean newPage = !Objects.equals(nextBox.getPage(), curBox.getPage());
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
    out.add(curBox);
    out.forEach(b -> b.setPage(null));
    return out;
  }

  /**
   * Convenience implementation to format document text that is available as a {@link String}.
   *
   * <p>Wraps the {@link String} in a {@link SourceReader} implementation and calls {@link
   * #format(Passage[], SourceReader)}
   *
   * @param passages in the the document text that contain highlighted text
   * @param content of the OCR field
   * @return the parsed snippet representation of the passages
   */
  @Override
  public Object format(Passage[] passages, String content) {
    OcrSnippet[] snips = this.format(passages, new StringSourceReader(content));
    return Arrays.stream(snips).map(OcrSnippet::getText).toArray(String[]::new);
  }

  /**
   * Truncate float to a precision of two digits after the decimal point.
   *
   * <p>Intended to keep the plugin response small and tidy.
   */
  private static float truncateFloat(float num) {
    return (float) Math.floor(num * 10000) / 10000;
  }

  protected static class PassageMatch {
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
      return (s1 <= s2 && s2 <= e1)
          || //   --------
          // -----

          (s1 <= e2 && e2 <= e1)
          || // --------
          //      -----

          (s2 <= s1 && s1 <= e2 && // --------
              s2 <= e1 && e1 <= e2); //   ---
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
      return String.format(Locale.US, "PassageMatch{start=%d, end=%d}", start, end);
    }
  }
}
