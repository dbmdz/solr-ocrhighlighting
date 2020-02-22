package de.digitalcollections.solrocr.formats.hocr;

import de.digitalcollections.solrocr.formats.OcrPassageFormatter;
import de.digitalcollections.solrocr.iter.IterableCharSequence;
import de.digitalcollections.solrocr.model.OcrBox;
import de.digitalcollections.solrocr.model.OcrPage;
import java.awt.Dimension;
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
  private final static Pattern pageElemPat = Pattern.compile("<div.+?class=['\"]ocr_page['\"]\\s*(?<attribs>.+?)>");
  private final static Pattern pageIdPat = Pattern.compile(
      "(?:id=['\"](?<id>.+?)['\"]|x_source (?<source>.+?)['\";]|ppageno (?<pageno>\\d+))");
  private final static Pattern pageBboxPat = Pattern.compile("bbox 0 0 (?<width>\\d+) (?<height>\\d+)");

  private final HocrClassBreakIterator pageIter;
  private final String startHlTag;
  private final String endHlTag;

  public HocrPassageFormatter(String startHlTag, String endHlTag, boolean absoluteHighlights) {
    super(startHlTag, endHlTag, absoluteHighlights);
    this.pageIter = new HocrClassBreakIterator("ocr_page");
    this.startHlTag = startHlTag;
    this.endHlTag = endHlTag;
  }

  private OcrPage parsePage(String pageAttribs, int pagePos) {
    RuntimeException noPageIdExc = new RuntimeException("Pages must have an identifier, check your source files!");
    if (pageAttribs == null) {
      throw noPageIdExc;
    }
    Matcher idMatch = pageIdPat.matcher(pageAttribs);
    String pageId;
    if (idMatch.find()) {
      pageId = Stream.of("id", "source", "pageno")
          .map(idMatch::group)
          .filter(StringUtils::isNotEmpty)
          .findFirst().orElseThrow(() -> noPageIdExc);
    } else {
      throw noPageIdExc;
    }
    Dimension pageDims = null;
    Matcher boxMatch = pageBboxPat.matcher(pageAttribs);
    if (boxMatch.find()) {
      pageDims = new Dimension(
          Integer.parseInt(boxMatch.group("width")),
          Integer.parseInt(boxMatch.group("height")));
    }
    return new OcrPage(pageId, pageDims);
  }

  @Override
  public OcrPage determineStartPage(String ocrFragment, int startOffset, IterableCharSequence content) {
    pageIter.setText(content);
    int pageOffset = pageIter.preceding(startOffset);
    String pageFragment = content.subSequence(
        pageOffset, Math.min(pageOffset + 256, content.length())).toString();
    Matcher m = pageElemPat.matcher(pageFragment);
    if (m.find()) {
      return parsePage(m.group("attribs"), m.start());
    }
    return null;
  }

  @Override
  protected TreeMap<Integer, OcrPage> parsePages(String ocrFragment) {
    TreeMap<Integer, OcrPage> map = new TreeMap<>();
    Matcher m = pageElemPat.matcher(ocrFragment);
    while (m.find()) {
      OcrPage page = parsePage(m.group("attribs"), m.start());
      map.put(m.start(), page);
    }
    return map;
  }

  @Override
  protected List<OcrBox> parseWords(String ocrFragment, TreeMap<Integer, OcrPage> pages, String startPage) {
    List<OcrBox> wordBoxes = new ArrayList<>();
    Matcher m = wordPat.matcher(ocrFragment);
    boolean inHighlight = false;
    while (m.find()) {
      String pageId = startPage;
      if (pages.floorKey(m.start()) != null) {
        pageId = pages.floorEntry(m.start()).getValue().id;
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
