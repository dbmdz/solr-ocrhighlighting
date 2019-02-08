package org.mdz.search.solrocr.formats.mini;

import com.google.common.collect.ImmutableSet;
import com.google.common.io.CharStreams;
import java.io.IOException;
import java.io.StringReader;
import java.text.StringCharacterIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.lucene.analysis.charfilter.HTMLStripCharFilter;
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
  private TagBreakIterator pageIter = new TagBreakIterator("p");
  private TagBreakIterator startContextIter;
  private TagBreakIterator endContextIter;
  private String contextTag;
  private String limitTag;
  private String startHlTag;
  private String endHlTag;

  public MiniOcrPassageFormatter(String contextTag, String limitTag, String startHlTag, String endHlTag) {
    this.contextTag = contextTag;
    this.startContextIter = new TagBreakIterator(contextTag);
    this.endContextIter = new TagBreakIterator(contextTag, true);
    this.startHlTag = startHlTag;
    this.endHlTag = endHlTag;
    this.limitTag = limitTag;
  }

  public OcrSnippet[] format(Passage[] passages, IterableCharSequence content) {
    OcrSnippet[] snippets = new OcrSnippet[passages.length];
    for (int i=0; i < passages.length; i++) {
      Passage passage = passages[i];
      StringBuilder sb = new StringBuilder(content.subSequence(passage.getStartOffset(), passage.getEndOffset()));
      int extraChars = 0;
      for (int j=0; j < passage.getNumMatches(); j++) {
        int matchStart = passage.getMatchStarts()[j] - passage.getStartOffset();
        sb.insert(extraChars + matchStart, startHlTag);
        extraChars += startHlTag.length();
        int matchEnd = passage.getMatchEnds()[j] - passage.getStartOffset();
        sb.insert(extraChars + matchEnd - 1, endHlTag);
        extraChars += endHlTag.length();
      }
      String xmlFragment = truncateSnippet(sb.toString());
      String pageId = determinePage(xmlFragment, passage.getStartOffset(), content);
      snippets[i] = parseSnippet(xmlFragment, pageId);
      snippets[i].setScore(passage.getScore());
    }
    return snippets;
  }

  private String determinePage(String xmlFragment, int startOffset, IterableCharSequence content) {
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

  private String truncateSnippet(String snippet) {
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

  private String getText(String xmlFragment) {
    HTMLStripCharFilter filter = new HTMLStripCharFilter(
        new StringReader(xmlFragment),
        ImmutableSet.of(startHlTag.substring(1, startHlTag.length() - 1)));
    try {
      return CharStreams.toString(filter).replaceAll("\n", "").trim();
    } catch (IOException e) {
      return xmlFragment;
    }
  }

  private OcrSnippet parseSnippet(String xmlFragment, String pageId) {
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
    OcrSnippet snip = new OcrSnippet(getText(xmlFragment), pageId, snippetRegion);
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
