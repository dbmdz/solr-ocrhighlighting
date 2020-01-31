package de.digitalcollections.solrocr.formats.hocr;

import de.digitalcollections.solrocr.formats.OcrPassageFormatter;
import de.digitalcollections.solrocr.util.IterableCharSequence;
import de.digitalcollections.solrocr.util.OcrBox;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.text.StringEscapeUtils;

public class HocrPassageFormatter extends OcrPassageFormatter {
  private final static Pattern wordPat = Pattern.compile(
      "<span class=['\"]ocrx_word['\"].+?title=['\"].*?"
      + "bbox (?<ulx>\\d+) (?<uly>\\d+) (?<lrx>\\d+) (?<lry>\\d+);?.*?>(?<text>.+?)</span>");
  private final static Pattern pageElemPat = Pattern.compile("<div.+?class=['\"]ocr_page['\"] ?(?<attribs>.+?)>");
  private final static Pattern pageIdPat = Pattern.compile(
      "(?:id=['\"](?<id>.+?)['\"]|x_source (?<source>.+?)['\";]|ppageno (?<pageno>\\d+))");

  private final HocrClassBreakIterator pageIter;
  private final String startHlTag;
  private final String endHlTag;

  public HocrPassageFormatter(String startHlTag, String endHlTag, boolean absoluteHighlights) {
    super(startHlTag, endHlTag, absoluteHighlights);
    this.pageIter = new HocrClassBreakIterator("ocr_page");
    this.startHlTag = startHlTag;
    this.endHlTag = endHlTag;
  }

  private String getPageId(String pageAttribs) {
    if (pageAttribs == null) {
      return null;
    }
    Matcher idMatch = pageIdPat.matcher(pageAttribs);
    if (idMatch.find()) {
      return Stream.of("id", "source", "pageno")
          .map(idMatch::group)
          .filter(StringUtils::isNotEmpty)
          .findFirst().orElse(null);
    }
    return null;
  }

  @Override
  public String determineStartPage(String ocrFragment, int startOffset, IterableCharSequence content) {
    pageIter.setText(content);
    int pageOffset = pageIter.preceding(startOffset);
    String pageFragment = content.subSequence(
        pageOffset, Math.min(pageOffset + 256, content.length())).toString();
    String pageId = getPageId(pageFragment);
    if (StringUtils.isEmpty(pageId)) {
      pageId = String.format("_unknown_%d", pageOffset);
    }
    return pageId;
  }

  private TreeMap<Integer, String> determinePageBreaks(String ocrFragment) {
    TreeMap<Integer, String> map = new TreeMap<>();
    Matcher m = pageElemPat.matcher(ocrFragment);
    while (m.find()) {
      String pageId = getPageId(m.group("attribs"));
      if (StringUtils.isEmpty(pageId)) {
        pageId = String.format("_unknown_%d", m.start());
      }
      map.put(m.start(), pageId);
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
