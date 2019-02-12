package org.mdz.search.solrocr.formats.alto;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.text.StringEscapeUtils;
import org.mdz.search.solrocr.formats.OcrPassageFormatter;
import org.mdz.search.solrocr.formats.OcrSnippet;
import org.mdz.search.solrocr.util.IterableCharSequence;
import org.mdz.search.solrocr.util.OcrBox;
import org.mdz.search.solrocr.util.TagBreakIterator;

public class AltoPassageFormatter extends OcrPassageFormatter {
  private final static Pattern pagePat = Pattern.compile("<Page ?(?<attribs>.+?)/?>");
  private final static Pattern wordPat = Pattern.compile("<String ?(?<attribs>.+?)/?>");
  private final static Pattern attribPat = Pattern.compile("(?<key>[A-Z]+?)=\"(?<val>.+?)\"");

  private final TagBreakIterator pageIter = new TagBreakIterator("Page");
  private final TagBreakIterator limitIter;

  protected AltoPassageFormatter(String contextTag, String limitTag, String startHlTag, String endHlTag) {
    super(startHlTag, endHlTag);
    this.limitIter = new TagBreakIterator(limitTag);
  }

  private Map<String, String> parseAttribs(String attribStr) {
    Map<String, String> attribs = new HashMap<>();
    Matcher m = attribPat.matcher(attribStr);
    while (m.find()) {
      attribs.put(m.group("key"), m.group("val"));
    }
    return attribs;
  }

  @Override
  protected String determinePage(String ocrFragment, int startOffset, IterableCharSequence content) {
    Matcher m = pagePat.matcher(ocrFragment);
    if (m.find()) {
      return parseAttribs(m.group("attribs")).get("ID");
    }
    pageIter.setText(content);
    int pageOffset = pageIter.preceding(startOffset);
    String pageFragment = content.subSequence(
        pageOffset, pageOffset + Math.min(512, content.length())).toString();
    m = pagePat.matcher(pageFragment);
    if (m.find()) {
      return parseAttribs(m.group("attribs")).get("ID");
    }
    return null;
  }

  private String extractText(String altoFragment) {
    StringBuilder sb = new StringBuilder(
        altoFragment
        .replaceAll("<SP.*?>", " ")
        .replaceAll("(</?)?TextLine.*?>", " ")
        .replaceAll("\n", "")
        .replaceAll("\\s+", " "));
    while (true) {
      Matcher m = wordPat.matcher(sb);
      if (!m.find()) {
        break;
      }
      String content = parseAttribs(m.group("attribs")).get("CONTENT");
      sb.replace(m.start(), m.end(), content);
    }
    return sb.toString().replaceAll("</?[A-Z]?.*?>?", "");
  }

  @Override
  protected String truncateFragment(String ocrFragment) {
    limitIter.setText(ocrFragment);
    int start = limitIter.preceding(ocrFragment.indexOf(startHlTag));
    int end = limitIter.following(ocrFragment.lastIndexOf(endHlTag));
    return ocrFragment.substring(start, end);
  }

  @Override
  protected OcrSnippet parseFragment(String ocrFragment, String pageId) {
    List<OcrBox> hlCoords = new ArrayList<>();
    int ulx = Integer.MAX_VALUE;
    int uly = Integer.MAX_VALUE;
    int lrx = -1;
    int lry = -1;
    ocrFragment = ocrFragment.replaceAll(startHlTag, "@@STARTHLTAG@@")
                             .replaceAll(endHlTag, "@@ENDHLTAG@@");
    Matcher m = wordPat.matcher(ocrFragment);
    while (m.find()) {
      Map<String, String> attribs = parseAttribs(m.group("attribs"));
      int x = Integer.parseInt(attribs.get("HPOS"));
      int y = Integer.parseInt(attribs.get("VPOS"));
      int w = Integer.parseInt(attribs.get("WIDTH"));
      int h = Integer.parseInt(attribs.get("HEIGHT"));
      if (x < ulx) {
        ulx = x;
      }
      if (y < uly) {
        uly = y;
      }
      if ((x + w) > lrx) {
        lrx = x + w;
      }
      if ((y + h) > lry) {
        lry = y + h;
      }
      String text = attribs.get("CONTENT");
      if (text.startsWith("@@STARTHLTAG@@")) {
        hlCoords.add(new OcrBox(x, y, w, h));
      }
    }
    final int snipX = ulx;
    final int snipY = uly;
    final int snipWidth = lrx - snipX;
    final int snipHeight = lry - snipY;
    OcrBox snippetRegion = new OcrBox(snipX, snipY, snipWidth, snipHeight);
    String text = StringEscapeUtils.unescapeXml(
        extractText(ocrFragment).replaceAll("@@STARTHLTAG@@", startHlTag)
                                .replaceAll("@@ENDHLTAG@@", endHlTag)).trim();
    OcrSnippet snip = new OcrSnippet(text,  pageId, snippetRegion);
    hlCoords.stream()
        .map(box -> new OcrBox(box.x - snipX, box.y - snipY, box.width, box.height))
        .forEach(snip::addHighlightRegion);
    return snip;
  }
}
