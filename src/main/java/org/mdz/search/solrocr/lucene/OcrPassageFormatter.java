package org.mdz.search.solrocr.lucene;

import com.google.common.collect.ImmutableSet;
import com.google.common.io.CharStreams;
import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.io.StringReader;
import java.text.StringCharacterIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.lucene.analysis.charfilter.HTMLStripCharFilter;
import org.apache.lucene.search.uhighlight.Passage;
import org.apache.lucene.search.uhighlight.PassageFormatter;
import org.mdz.search.solrocr.util.FileCharIterator;
import org.mdz.search.solrocr.util.TagBreakIterator;

public class OcrPassageFormatter  extends PassageFormatter {
  private final static Pattern wordPat = Pattern.compile(
      "<w x=\"(?<x>.+?)\" y=\"(?<y>.+?)\" w=\"(?<w>.+?)\" h=\"(?<h>.+?)\">(?<text>.+?)</w>");
  private final static Pattern pagePat = Pattern.compile("<p xml:id=\"(?<pageId>.+?)\">");
  private final TagBreakIterator pageIter = new TagBreakIterator("p");
  private final String contextTag;
  private final TagBreakIterator startContextIter;
  private final TagBreakIterator endContextIter;

  public OcrPassageFormatter(String contextTag) {
    this.contextTag = contextTag;
    this.startContextIter = new TagBreakIterator(contextTag);
    this.endContextIter = new TagBreakIterator(contextTag, true);
  }

  public OcrSnippet[] format(Passage[] passages, FileCharIterator content) {
    // FIXME: Make <em/> configurable!
    OcrSnippet[] snippets = new OcrSnippet[passages.length];
    for (int i=0; i < passages.length; i++) {
      Passage passage = passages[i];
      StringBuilder sb = new StringBuilder(content.subSequence(passage.getStartOffset(), passage.getEndOffset()));
      int extraChars = 0;
      for (int j=0; j < passage.getNumMatches(); j++) {
        int matchStart = passage.getMatchStarts()[j] - passage.getStartOffset();
        sb.insert(extraChars + matchStart, "<em>");
        extraChars += 4;
        int matchEnd = passage.getMatchEnds()[j] - passage.getStartOffset();
        sb.insert(extraChars + matchEnd - 1, "</em>");
        extraChars += 5;
      }
      String xmlFragment = truncateSnippet(sb.toString());
      String pageId = determinePage(xmlFragment, passage.getStartOffset(), content);
      snippets[i] = parseSnippet(xmlFragment, pageId);
      snippets[i].setScore(passage.getScore());
    }
    return snippets;
  }

  private String determinePage(String xmlFragment, int startOffset, FileCharIterator content) {
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
    // FIXME: Make <b/> and its width configurable!
    // FIXME: Make <em/> and its width configurable!
    int startBlock = snippet.indexOf("<b>");
    if (startBlock > -1 && startBlock < snippet.indexOf("<em>")) {
      snippet = snippet.substring(startBlock + 4);
    }
    int endBlock = snippet.lastIndexOf("</b>");
    if (endBlock > -1 && endBlock > snippet.lastIndexOf("</em>")) {
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
      snippet = snippet.substring(0, lastWord - contextEndTagSize);
    }
    return snippet;
  }

  private String getText(String xmlFragment) {
    HTMLStripCharFilter filter = new HTMLStripCharFilter(new StringReader(xmlFragment), ImmutableSet.of("em"));
    try {
      return CharStreams.toString(filter).replaceAll("\n", "").trim();
    } catch (IOException e) {
      return xmlFragment;
    }
  }

  private OcrSnippet parseSnippet(String xmlFragment, String pageId) {
    List<Rectangle2D> hlCoords = new ArrayList<>();
    double ulx = Double.MAX_VALUE;
    double uly = Double.MAX_VALUE;
    double lrx = -1;
    double lry = -1;
    Matcher m = wordPat.matcher(xmlFragment);
    while (m.find()) {
      double x = Double.valueOf(m.group("x"));
      double y = Double.valueOf(m.group("y"));
      double width = Double.valueOf(m.group("w"));
      double height = Double.valueOf(m.group("h"));
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
      if (text.startsWith("<em>")) {
        hlCoords.add(new Rectangle2D.Double(x, y, width, height));
      }
    }
    Rectangle2D snippetRegion = new Rectangle2D.Double(
        ulx, uly,
        // Truncate double precision so we get a more compact output
        Math.floor((lrx - ulx) * 10000) / 10000,
        Math.floor((lry - uly) * 10000) / 10000);
    OcrSnippet snip = new OcrSnippet(getText(xmlFragment), pageId, snippetRegion);
    hlCoords.forEach(snip::addHighlightRegion);
    return snip;
  }

  @Override
  public Object format(Passage[] passages, String content) {
    throw new UnsupportedOperationException();
  }
}
