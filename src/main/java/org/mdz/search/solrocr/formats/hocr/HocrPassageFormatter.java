package org.mdz.search.solrocr.formats.hocr;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.mdz.search.solrocr.formats.OcrPassageFormatter;
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
  protected List<OcrBox> parseWords(String ocrFragment) {
    List<OcrBox> wordBoxes = new ArrayList<>();
    Matcher m = wordPat.matcher(ocrFragment);
    boolean inHighlight = false;
    while (m.find()) {
      int x0 = Integer.parseInt(m.group("ulx"));
      int y0 = Integer.parseInt(m.group("uly"));
      int x1 = Integer.parseInt(m.group("lrx"));
      int y1 = Integer.parseInt(m.group("lry"));
      String text = m.group("text");
      if (text.contains(startHlTag)) {
        inHighlight = true;
      }
      wordBoxes.add(new OcrBox(text.replace(startHlTag, "").replace(endHlTag, ""),
                               x0, y0, x1, y1, inHighlight));
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

  @Override
  protected BreakIterator getPageBreakIterator() {
    return pageIter;
  }

  @Override
  protected BreakIterator getLimitBreakIterator() {
    return limitIter;
  }
}
