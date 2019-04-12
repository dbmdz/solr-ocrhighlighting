package org.mdz.search.solrocr.formats.alto;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
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
  public String determinePage(String ocrFragment, int startOffset, IterableCharSequence content) {
    pageIter.setText(content);
    int pageOffset = pageIter.preceding(startOffset);
    String pageFragment = content.subSequence(
        pageOffset, Math.min(pageOffset + 512, content.length())).toString();
    Matcher m = pagePat.matcher(pageFragment);
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
  protected OcrSnippet parseFragment(String ocrFragment, String pageId) {
    List<List<OcrBox>> hlBoxes = new ArrayList<>();
    int ulx = Integer.MAX_VALUE;
    int uly = Integer.MAX_VALUE;
    int lrx = -1;
    int lry = -1;
    ocrFragment = ocrFragment.replaceAll(startHlTag, "@@STARTHLTAG@@")
                             .replaceAll(endHlTag, "@@ENDHLTAG@@");
    Matcher m = wordPat.matcher(ocrFragment);
    List<OcrBox> currentHl = null;
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
      if (text.contains("@@STARTHLTAG@@")) {
        currentHl = new ArrayList<>();
      }
      if (currentHl != null) {
        currentHl.add(new OcrBox(text.replace("@@STARTHLTAG@@", "")
                                     .replace("@@ENDHLTAG@@", ""),
                                 x, y, x + w, y + h));
      }
      if (text.contains("@@ENDHLTAG@@") && currentHl != null) {
        hlBoxes.add(currentHl);
        currentHl = null;
      }
    }
    final int snipX = ulx;
    final int snipY = uly;
    OcrBox snippetRegion = new OcrBox(null, ulx, uly, lrx, lry);
    String text = StringEscapeUtils.unescapeXml(
        extractText(ocrFragment).replaceAll("@@STARTHLTAG@@", startHlTag)
                                .replaceAll("@@ENDHLTAG@@", endHlTag)).trim();
    OcrSnippet snip = new OcrSnippet(text,  pageId, snippetRegion);
    hlBoxes.stream()
        .map(bs -> bs.stream()
            .map(b -> new OcrBox(b.text, b.ulx - snipX, b.uly - snipY,
                                 b.lrx - snipX, b.lry - snipY))
            .collect(Collectors.toList()))
        .forEach(bs -> snip.addHighlightRegion(this.mergeBoxes(bs)));
    return snip;
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
