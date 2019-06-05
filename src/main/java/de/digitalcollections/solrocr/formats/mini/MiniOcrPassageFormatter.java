package de.digitalcollections.solrocr.formats.mini;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.lucene.search.uhighlight.Passage;
import de.digitalcollections.solrocr.formats.OcrPassageFormatter;
import de.digitalcollections.solrocr.formats.OcrSnippet;
import de.digitalcollections.solrocr.util.IterableCharSequence;
import de.digitalcollections.solrocr.util.OcrBox;
import de.digitalcollections.solrocr.util.TagBreakIterator;

public class MiniOcrPassageFormatter extends OcrPassageFormatter {
  private final static Pattern wordPat = Pattern.compile(
      "<w x=\"(?<x>1?\\.?\\d+?) (?<y>1?\\.?\\d+?) (?<w>1?\\.?\\d+?) (?<h>1?\\.?\\d+?)\">(?<text>.+?)</w>");
  private final static Pattern pagePat = Pattern.compile("<p xml:id=\"(?<pageId>.+?)\">");

  private final TagBreakIterator pageIter = new TagBreakIterator("p");

  public MiniOcrPassageFormatter(String startHlTag, String endHlTag, boolean absoluteHighlights) {
    super(startHlTag, endHlTag, absoluteHighlights);
  }

  @Override
  public String determineStartPage(String xmlFragment, int startOffset, IterableCharSequence content) {
    pageIter.setText(content);
    int pageOffset = pageIter.preceding(startOffset);
    String pageFragment = content.subSequence(
        pageOffset, Math.min(pageOffset + 128, content.length())).toString();
    Matcher m = pagePat.matcher(pageFragment);
    if (m.find()) {
      return m.group("pageId");
    }
    return null;
  }

  private TreeMap<Integer, String> determinePageBreaks(String ocrFragment) {
    TreeMap<Integer, String> map = new TreeMap<>();
    Matcher m = pagePat.matcher(ocrFragment);
    while (m.find()) {
      map.put(m.start(), m.group("pageId"));
    }
    return map;
  }

  @Override
  protected void addHighlightsToSnippet(List<List<OcrBox>> hlBoxes, OcrSnippet snippet) {
    if (this.absoluteHighlights) {
      super.addHighlightsToSnippet(hlBoxes, snippet);
      return;
    }

    // Handle relative coordinates
    OcrBox snip = snippet.getSnippetRegions().get(0);
    float xOffset = snip.getUlx();
    float yOffset = snip.getUly();
    float snipWidth = snip.getLrx() - xOffset;
    float snipHeight = snip.getLry() - yOffset;
    hlBoxes.stream()
        .map(cs -> cs.stream().map(
            b -> new OcrBox(
                b.getText(),
                b.getPageId(),
              truncateFloat((b.getUlx() - xOffset) / snipWidth),
              truncateFloat((b.getUly() - yOffset) / snipHeight),
              truncateFloat((b.getLrx() - xOffset) / snipWidth),
              truncateFloat((b.getLry() - yOffset) / snipHeight),
                b.isHighlight()))
          .collect(Collectors.toList()))
        .map(this::mergeBoxes)
        .forEach(snippet::addHighlightRegion);
  }

  @Override
  protected List<OcrBox> parseWords(String ocrFragment, String startPage) {
    List<OcrBox> wordBoxes = new ArrayList<>();
    boolean inHighlight = false;
    TreeMap<Integer, String> pageBreaks = determinePageBreaks(ocrFragment);
    Matcher m = wordPat.matcher(ocrFragment);
    while (m.find()) {
      String pageId = startPage;
      if (pageBreaks.floorKey(m.start()) != null) {
        pageId = pageBreaks.floorEntry(m.start()).getValue();
      }
      float x = Float.valueOf(m.group("x"));
      float y = Float.valueOf(m.group("y"));
      float width = Float.valueOf(m.group("w"));
      float height = Float.valueOf(m.group("h"));
      String text = m.group("text");
      if (text.contains(startHlTag)) {
        inHighlight = true;
      }
      wordBoxes.add(new OcrBox(text.replace(startHlTag, "").replace(endHlTag, ""),
                               pageId, x, y, x + width, y + height, inHighlight));
      boolean endOfHl = (
          text.contains(endHlTag)
          || ocrFragment.substring(m.end(), Math.min(m.end() + endHlTag.length(), ocrFragment.length()))
              .equals(endHlTag));
      if (endOfHl) {
        inHighlight = false;
      }
    }
    return wordBoxes;
  }

  private float truncateFloat(float num) {
    return (float) Math.floor(num * 10000) / 10000;
  }

  @Override
  public Object format(Passage[] passages, String content) {
    throw new UnsupportedOperationException();
  }
}
