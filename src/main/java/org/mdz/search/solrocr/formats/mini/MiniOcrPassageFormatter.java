package org.mdz.search.solrocr.formats.mini;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.lucene.search.uhighlight.Passage;
import org.mdz.search.solrocr.formats.OcrPassageFormatter;
import org.mdz.search.solrocr.formats.OcrSnippet;
import org.mdz.search.solrocr.util.IterableCharSequence;
import org.mdz.search.solrocr.util.OcrBox;
import org.mdz.search.solrocr.util.TagBreakIterator;

public class MiniOcrPassageFormatter extends OcrPassageFormatter {
  private final static Pattern wordPat = Pattern.compile(
      "<w x=\"(?<x>\\.?\\d+?) (?<y>\\.?\\d+?) (?<w>\\.?\\d+?) (?<h>\\.?\\d+?)\">(?<text>.+?)</w>");
  private final static Pattern pagePat = Pattern.compile("<p xml:id=\"(?<pageId>.+?)\">");

  private final TagBreakIterator pageIter = new TagBreakIterator("p");
  private final TagBreakIterator limitIter;

  public MiniOcrPassageFormatter(String contextTag, String limitTag, String startHlTag, String endHlTag,
                                 boolean absoluteHighlights) {
    super(startHlTag, endHlTag, absoluteHighlights);
    this.limitIter = new TagBreakIterator(limitTag);
  }

  @Override
  public String determinePage(String xmlFragment, int startOffset, IterableCharSequence content) {
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

  @Override
  protected void addHighlightsToSnippet(List<List<OcrBox>> hlBoxes, OcrSnippet snippet) {
    if (this.absoluteHighlights) {
      super.addHighlightsToSnippet(hlBoxes, snippet);
      return;
    }

    // Handle relative coordinates
    float xOffset = snippet.getSnippetRegion().ulx;
    float yOffset = snippet.getSnippetRegion().uly;
    float snipWidth = snippet.getSnippetRegion().lrx - xOffset;
    float snipHeight = snippet.getSnippetRegion().lry - yOffset;
    hlBoxes.stream()
        .map(cs -> cs.stream().map(
            b -> new OcrBox(
              b.text,
              truncateFloat((b.ulx - xOffset) / snipWidth),
              truncateFloat((b.uly - yOffset) / snipHeight),
              truncateFloat((b.lrx - xOffset) / snipWidth),
              truncateFloat((b.lry - yOffset) / snipHeight),
              b.isHighlight))
          .collect(Collectors.toList()))
        .map(this::mergeBoxes)
        .forEach(snippet::addHighlightRegion);
  }

  @Override
  protected List<OcrBox> parseWords(String ocrFragment) {
    List<OcrBox> wordBoxes = new ArrayList<>();
    boolean inHighlight = false;
    Matcher m = wordPat.matcher(ocrFragment);
    while (m.find()) {
      float x = Float.valueOf(m.group("x"));
      float y = Float.valueOf(m.group("y"));
      float width = Float.valueOf(m.group("w"));
      float height = Float.valueOf(m.group("h"));
      String text = m.group("text");
      if (text.contains(startHlTag)) {
        inHighlight = true;
      }
      wordBoxes.add(new OcrBox(text.replace(startHlTag, "").replace(endHlTag, ""),
                               x, y, x + width, y + height, inHighlight));
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

  @Override
  protected BreakIterator getPageBreakIterator() {
    return pageIter;
  }

  @Override
  protected BreakIterator getLimitBreakIterator() {
    return limitIter;
  }
}
