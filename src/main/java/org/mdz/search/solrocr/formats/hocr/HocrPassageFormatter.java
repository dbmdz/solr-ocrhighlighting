package org.mdz.search.solrocr.formats.hocr;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.mdz.search.solrocr.formats.OcrPassageFormatter;
import org.mdz.search.solrocr.formats.OcrSnippet;
import org.mdz.search.solrocr.util.IterableCharSequence;
import org.mdz.search.solrocr.util.OcrBox;

public class HocrPassageFormatter extends OcrPassageFormatter {
  private final static Pattern wordPat = Pattern.compile(
      "<span class=['\"]ocrx_word['\"].+?title=['\"].*?"
      + "bbox (?<ulx>\\d+) (?<uly>\\d+) (?<lrx>\\d+) (?<lry>\\d+);?.*?>(?<text>.+?)</span>");
  private final static Pattern pagePat = Pattern.compile(
      "<div.+?class=['\"]ocr_page['\"].+?id=['\"](?<pageId>.+?)['\"]");

  private final HocrClassBreakIterator pageIter;
  private final HocrClassBreakIterator limitIter;
  private final String startHlTag;
  private final String endHlTag;

  public HocrPassageFormatter(String contextClass, String limitClass, String startHlTag, String endHlTag,
                              boolean absoluteHighlights) {
    super(startHlTag, endHlTag, absoluteHighlights);
    this.pageIter = new HocrClassBreakIterator("ocr_page");
    this.limitIter = new HocrClassBreakIterator(limitClass);
    this.startHlTag = startHlTag;
    this.endHlTag = endHlTag;
  }

  @Override
  public String determinePage(String ocrFragment, int startOffset, IterableCharSequence content) {
    pageIter.setText(content);
    int pageOffset = pageIter.preceding(startOffset);
    String pageFragment = content.subSequence(
        pageOffset, Math.min(pageOffset + 256, content.length())).toString();
    Matcher m = pagePat.matcher(pageFragment);
    if (m.find()) {
      return m.group("pageId");
    }
    return null;
  }

  @Override
  protected OcrSnippet parseFragment(String ocrFragment, String pageId) {
    List<List<OcrBox>> hlBoxes = new ArrayList<>();
    int ulx = Integer.MAX_VALUE;
    int uly = Integer.MAX_VALUE;
    int lrx = -1;
    int lry = -1;
    List<OcrBox> currentHl = null;
    Matcher m = wordPat.matcher(ocrFragment);
    while (m.find()) {
      int x0 = Integer.valueOf(m.group("ulx"));
      int y0 = Integer.valueOf(m.group("uly"));
      int x1 = Integer.valueOf(m.group("lrx"));
      int y1 = Integer.valueOf(m.group("lry"));
      if (x0 < ulx) {
        ulx = x0;
      }
      if (y0 < uly) {
        uly = y0;
      }
      if (x1 > lrx) {
        lrx = x1;
      }
      if (y1 > lry) {
        lry = y1;
      }
      String text = m.group("text");
      if (text.contains(startHlTag)) {
        currentHl = new ArrayList<>();
      }
      if (currentHl != null) {
        currentHl.add(new OcrBox(text.replace(startHlTag, "").replace(endHlTag, ""),
                                 x0, y0, x1, y1));
      }
      if (currentHl != null
          && (text.contains(endHlTag)
              || ocrFragment.substring(m.end(), Math.min(m.end() + endHlTag.length(),ocrFragment.length()))
                            .equals(endHlTag))) {
        hlBoxes.add(currentHl);
        currentHl = null;
      }
    }
    int snipX = ulx;
    int snipY = uly;
    OcrBox snippetRegion = new OcrBox(null, ulx, uly, lrx, lry);
    OcrSnippet snip = new OcrSnippet(getTextFromXml(ocrFragment), pageId, snippetRegion);
    this.addHighlightsToSnippet(hlBoxes, snip);
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
