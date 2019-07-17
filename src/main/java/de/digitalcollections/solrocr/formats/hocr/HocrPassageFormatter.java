package de.digitalcollections.solrocr.formats.hocr;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import de.digitalcollections.solrocr.formats.OcrPassageFormatter;
import de.digitalcollections.solrocr.util.IterableCharSequence;
import de.digitalcollections.solrocr.util.OcrBox;
import org.apache.commons.text.StringEscapeUtils;

public class HocrPassageFormatter extends OcrPassageFormatter {
  private final static Pattern wordPat = Pattern.compile(
      "<span class=['\"]ocrx_word['\"].+?title=['\"].*?"
      + "bbox (?<ulx>\\d+) (?<uly>\\d+) (?<lrx>\\d+) (?<lry>\\d+);?.*?>(?<text>.+?)</span>");
  private final static Pattern pagePat = Pattern.compile(
      "<div.+?class=['\"]ocr_page['\"].+?id=['\"](?<pageId>.+?)['\"]");

  private final HocrClassBreakIterator pageIter;
  private final String startHlTag;
  private final String endHlTag;

  public HocrPassageFormatter(String startHlTag, String endHlTag, boolean absoluteHighlights) {
    super(startHlTag, endHlTag, absoluteHighlights);
    this.pageIter = new HocrClassBreakIterator("ocr_page");
    this.startHlTag = startHlTag;
    this.endHlTag = endHlTag;
  }

  @Override
  public String determineStartPage(String ocrFragment, int startOffset, IterableCharSequence content) {
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

  private TreeMap<Integer, String> determinePageBreaks(String ocrFragment) {
    TreeMap<Integer, String> map = new TreeMap<>();
    Matcher m = pagePat.matcher(ocrFragment);
    while (m.find()) {
      map.put(m.start(), m.group("pageId"));
    }
    return map;
  }

  @Override
  protected List<OcrBox> parseWords(String ocrFragment, String startPage) {
    List<OcrBox> wordBoxes = new ArrayList<>();
    TreeMap<Integer, String> pageBreaks = determinePageBreaks(ocrFragment);
    Matcher m = wordPat.matcher(ocrFragment);
    boolean inHighlight = false;
    while (m.find()) {
      String pageId = startPage;
      if (pageBreaks.floorKey(m.start()) != null) {
        pageId = pageBreaks.floorEntry(m.start()).getValue();
      }
      int x0 = Integer.parseInt(m.group("ulx"));
      int y0 = Integer.parseInt(m.group("uly"));
      int x1 = Integer.parseInt(m.group("lrx"));
      int y1 = Integer.parseInt(m.group("lry"));
      String text = StringEscapeUtils.unescapeXml(m.group("text"));
      if (text.contains(startHlTag)) {
        inHighlight = true;
      }
      wordBoxes.add(new OcrBox(text.replace(startHlTag, "").replace(endHlTag, ""),
                               pageId, x0, y0, x1, y1, inHighlight));
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
}
