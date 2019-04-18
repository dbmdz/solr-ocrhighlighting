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
  protected OcrSnippet parseFragment(String xmlFragment, String pageId) {
    List<List<OcrBox>> hlBoxes = new ArrayList<>();
    float ulx = Float.MAX_VALUE;
    float uly = Float.MAX_VALUE;
    float lrx = -1;
    float lry = -1;
    Matcher m = wordPat.matcher(xmlFragment);
    List<OcrBox> currentHl = null;
    while (m.find()) {
      float x = Float.valueOf(m.group("x"));
      float y = Float.valueOf(m.group("y"));
      float width = Float.valueOf(m.group("w"));
      float height = Float.valueOf(m.group("h"));
      if (x < ulx) {
        ulx = x;
      }
      if (y < uly) {
        uly = y;
      }
      if ((x + width) > lrx) {
        lrx = x + width;
      }
      if ((y + height) > lry) {
        lry = y + height;
      }
      String text = m.group("text");
      if (text.contains(startHlTag)) {
        currentHl = new ArrayList<>();
      }
      if (currentHl != null) {
        currentHl.add(new OcrBox(text.replace(startHlTag, "").replace(endHlTag, ""),
                                 x, y, x + width, y + height));
      }
      if (text.contains(endHlTag) && currentHl != null) {
        hlBoxes.add(currentHl);
        currentHl = null;
      }
    }
    OcrBox snippetRegion;
    final float xOffset = this.absoluteHighlights ? 0 : ulx;
    final float yOffset = this.absoluteHighlights ? 0 : uly;
    final float snipWidth = lrx - ulx;
    final float snipHeight = lry - uly;
    if (lrx < 1) {
      // Relative snippets
      snippetRegion = new OcrBox(null, ulx, uly, lrx, lry);
      hlBoxes = hlBoxes.stream()
        .map(cs -> cs.stream()
            .map(b -> new OcrBox(b.text,
                                 truncateFloat((b.ulx - xOffset) / snipWidth),
                                 truncateFloat((b.uly - yOffset) / snipHeight),
                                 truncateFloat((b.lrx - xOffset) / snipWidth),
                                 truncateFloat((b.lry - yOffset) / snipHeight)))
           .collect(Collectors.toList()))
        .map(this::mergeBoxes)
        .collect(Collectors.toList());
    } else {
      snippetRegion = new OcrBox(null, ulx, uly, lrx, lry);
      hlBoxes = hlBoxes.stream()
        .map(cs -> cs.stream()
            .map(b -> new OcrBox(b.text,
                                 (b.ulx - xOffset), (b.uly - yOffset),
                                 (b.lrx - xOffset), (b.lry - yOffset)))
            .collect(Collectors.toList()))
        .map(this::mergeBoxes)
        .collect(Collectors.toList());
      }
      OcrSnippet snip = new OcrSnippet(getTextFromXml(xmlFragment), pageId, snippetRegion);
    hlBoxes.forEach(snip::addHighlightRegion);
    return snip;
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
