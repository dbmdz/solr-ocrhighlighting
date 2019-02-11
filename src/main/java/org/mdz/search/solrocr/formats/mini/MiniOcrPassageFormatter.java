package org.mdz.search.solrocr.formats.mini;

import java.text.StringCharacterIterator;
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
  private final TagBreakIterator startContextIter;
  private final TagBreakIterator endContextIter;
  private final String contextTag;
  private final String limitTag;

  public MiniOcrPassageFormatter(String contextTag, String limitTag, String startHlTag, String endHlTag) {
    super(startHlTag, endHlTag);
    this.contextTag = contextTag;
    this.startContextIter = new TagBreakIterator(contextTag);
    this.endContextIter = new TagBreakIterator(contextTag, true);
    this.limitTag = limitTag;
  }

  @Override
  protected String determinePage(String xmlFragment, int startOffset, IterableCharSequence content) {
    Matcher m = pagePat.matcher(xmlFragment);
    if (m.find()) {
      return m.group("pageId");
    }
    pageIter.setText(content);
    int pageOffset = pageIter.preceding(startOffset);
    String pageFragment = content.subSequence(
        pageOffset, pageOffset + Math.min(128, content.length())).toString();
    m = pagePat.matcher(pageFragment);
    if (m.find()) {
      return m.group("pageId");
    }
    return null;
  }

  @Override
  protected String truncateFragment(String snippet) {
    int startBlock = snippet.indexOf("<" + limitTag + ">");
    if (startBlock > -1 && startBlock < snippet.indexOf(startHlTag)) {
      snippet = snippet.substring(startBlock + limitTag.length() + 2);
    }
    int endBlock = snippet.lastIndexOf("</" + limitTag + ">");
    if (endBlock > -1 && endBlock > snippet.lastIndexOf(endHlTag)) {
      snippet = snippet.substring(0, endBlock);
    }
    startContextIter.setText(new StringCharacterIterator(snippet));
    int firstWord = startContextIter.first();
    if (firstWord > 0) {
      snippet = snippet.substring(firstWord);
    }
    endContextIter.setText(new StringCharacterIterator(snippet));
    int lastWord = endContextIter.last();
    int contextEndTagSize = this.contextTag.length() - 3;
    if (lastWord < snippet.length() - contextEndTagSize) {
      snippet = snippet.substring(0, lastWord - contextEndTagSize - 1);
    }
    return snippet;
  }

  @Override
  protected OcrSnippet parseFragment(String xmlFragment, String pageId) {
    List<OcrBox> hlCoords = new ArrayList<>();
    float ulx = Float.MAX_VALUE;
    float uly = Float.MAX_VALUE;
    float lrx = -1;
    float lry = -1;
    Matcher m = wordPat.matcher(xmlFragment);
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
      if (text.startsWith(startHlTag)) {
        hlCoords.add(new OcrBox(x, y, width, height));
      }
    }
    OcrBox snippetRegion;
    final float snipX = ulx;
    final float snipY = uly;
    final float snipWidth = lrx - ulx;
    final float snipHeight = lry - uly;
    if (lrx < 1) {
      // Relative snippets
      snippetRegion = new OcrBox(ulx, uly, truncateFloat(snipWidth), truncateFloat(snipHeight));
      hlCoords = hlCoords.stream()
          .map(box -> {
            float hlWidth = box.width / snipWidth;
            float hlHeight = box.height / snipHeight;
            float hlX = (box.x - snipX) / snipWidth;
            float hlY = (box.y - snipY) / snipHeight;
            return new OcrBox(truncateFloat(hlX), truncateFloat(hlY), truncateFloat(hlWidth), truncateFloat(hlHeight));
          }).collect(Collectors.toList());
    } else {
      snippetRegion = new OcrBox(ulx, uly, (lrx - ulx), (lry - uly));
      hlCoords = hlCoords.stream()
          .map(box -> new OcrBox(box.x - snipX, box.y - snipY, box.width, box.height))
          .collect(Collectors.toList());
    }
    OcrSnippet snip = new OcrSnippet(getTextFromXml(xmlFragment), pageId, snippetRegion);
    hlCoords.forEach(snip::addHighlightRegion);
    return snip;
  }

  private float truncateFloat(float num) {
    return (float) Math.floor(num * 10000) / 10000;
  }

  @Override
  public Object format(Passage[] passages, String content) {
    throw new UnsupportedOperationException();
  }
}
