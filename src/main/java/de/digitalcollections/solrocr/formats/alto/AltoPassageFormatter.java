package de.digitalcollections.solrocr.formats.alto;

import de.digitalcollections.solrocr.formats.OcrPassageFormatter;
import de.digitalcollections.solrocr.iter.IterableCharSequence;
import de.digitalcollections.solrocr.model.OcrBox;
import de.digitalcollections.solrocr.model.OcrPage;
import de.digitalcollections.solrocr.iter.TagBreakIterator;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.text.StringEscapeUtils;

public class AltoPassageFormatter extends OcrPassageFormatter {

  private final static String START_HL = "@@STARTHLTAG@@";
  private final static String END_HL = "@@ENDHLTAG@@";
  private final static Pattern pagePat = Pattern.compile("<Page ?(?<attribs>.+?)/?>");
  private final static Pattern wordPat = Pattern.compile("<String ?(?<attribs>.+?)/?>");
  private final static Pattern attribPat = Pattern.compile("(?<key>[A-Z_]+?)=\"(?<val>.+?)\"");

  private final TagBreakIterator pageIter = new TagBreakIterator("Page");

  protected AltoPassageFormatter(String startHlTag, String endHlTag, boolean absoluteHighlights) {
    super(startHlTag, endHlTag, absoluteHighlights);
  }

  private Map<String, String> parseAttribs(String attribStr) {
    Map<String, String> attribs = new HashMap<>();
    Matcher m = attribPat.matcher(attribStr);
    while (m.find()) {
      attribs.put(m.group("key"), m.group("val"));
    }
    return attribs;
  }

  private OcrPage parsePage(Map<String, String> attribs) {
    Dimension dims = null;
    if (attribs.containsKey("WIDTH") && attribs.containsKey("HEIGHT")) {
      try {
        dims = new Dimension((int) Double.parseDouble(attribs.get("WIDTH")),
                (int) Double.parseDouble(attribs.get("HEIGHT")));
      } catch (NumberFormatException e) {
        // NOP, we're only interested in integer dimensions
      }
    }
    return new OcrPage(attribs.get("ID"), dims);
  }

  @Override
  public OcrPage determineStartPage(String ocrFragment, int startOffset, IterableCharSequence content) {
    pageIter.setText(content);
    int pageOffset = pageIter.preceding(startOffset);
    String pageFragment = content.subSequence(
        pageOffset, Math.min(pageOffset + 512, content.length())).toString();
    Matcher m = pagePat.matcher(pageFragment);
    if (m.find()) {
      Map<String, String> attribs = parseAttribs(m.group("attribs"));
      return parsePage(attribs);
    }
    return null;
  }

  @Override
  protected TreeMap<Integer, OcrPage> parsePages(String ocrFragment) {
    TreeMap<Integer, OcrPage> map = new TreeMap<>();
    Matcher m = pagePat.matcher(ocrFragment);
    while (m.find()) {
      map.put(m.start(), parsePage(parseAttribs(m.group("attribs"))));
    }
    return map;
  }

  @Override
  protected String getTextFromXml(String altoFragment) {
    // NOTE: We have to replace the original start/end highlight tags with non-XML placeholders so we don't break
    //       our rudimentary XML "parsing" to get to the attributes
    StringBuilder sb = new StringBuilder(
        altoFragment
            .replaceAll(startHlTag, START_HL)
            .replaceAll(endHlTag, END_HL)
            .replaceAll("<SP.*?>", " ")
            .replaceAll("(</?)?TextLine.*?>", " ")
            .replaceAll("(?s)<Description>.+?</Description>", ""));
    boolean isBeginning = true;
    while (true) {
      Matcher m = wordPat.matcher(sb);
      if (!m.find()) {
        break;
      }
      int start = m.start();
      int end = m.end();
      Map<String, String> attribs = parseAttribs(m.group("attribs"));
      String content;
      if ("HypPart1".equals(attribs.get("SUBS_TYPE"))) {
        if (m.find()) {  // The hyphen end is part of the fragment, so we use the dehyphenated form
          content = attribs.get("SUBS_CONTENT");
        } else {  // The hyphen end is not part of the fragment, so we use the hyphenated form
          content = attribs.get("CONTENT");
        }
      } else if ("HypPart2".equals(attribs.get("SUBS_TYPE"))) {
        if (isBeginning) {  // The hyphen start is not part of the fragment, so we use the hyphenated form
          content = attribs.get("CONTENT");
        } else {  // The hyphen start is part of the fragment, so the dehyphenated form is already in the plaintext
          content = "";
        }
      } else {
        content = attribs.get("CONTENT");
      }
      sb.replace(start, end, content);
      isBeginning = false;
    }
    return StringEscapeUtils.unescapeXml(sb.toString().replaceAll("</?[A-Z]?.*?>", ""))
        .replaceAll("\n", "")
        .replaceAll("\\s+", " ")
        .trim()
        .replaceAll(START_HL, startHlTag)
        .replaceAll(END_HL, endHlTag);
  }

  @Override
  protected List<OcrBox> parseWords(String ocrFragment, TreeMap<Integer, OcrPage> pages, String startPage) {
    // NOTE: We have to replace the original start/end highlight tags with non-XML placeholders so we don't break
    //       our rudimentary XML "parsing" to get to the attributes
    ocrFragment = ocrFragment
        .replaceAll(startHlTag, START_HL)
        .replaceAll(endHlTag, END_HL);
    List<OcrBox> wordBoxes = new ArrayList<>();
    Matcher m = wordPat.matcher(ocrFragment);
    boolean inHighlight = false;
    boolean highlightHyphenEnd = false;
    while (m.find()) {
      String pageId = startPage;
      if (pages.floorKey(m.start()) != null) {
        pageId = pages.floorEntry(m.start()).getValue().id;
      }
      Map<String, String> attribs = parseAttribs(m.group("attribs"));
      int x = (int) Double.parseDouble(attribs.get("HPOS"));
      int y = (int) Double.parseDouble(attribs.get("VPOS"));
      int w = (int) Double.parseDouble(attribs.get("WIDTH"));
      int h = (int) Double.parseDouble(attribs.get("HEIGHT"));
      String subsType = attribs.get("SUBS_TYPE");

      String text = StringEscapeUtils.unescapeXml(attribs.get("CONTENT"));
      if ("HypPart1".equals(subsType)) {
        text += "-";
      }
      if (text.contains(START_HL) || attribs.getOrDefault("SUBS_CONTENT", "").contains(START_HL)) {
        inHighlight = true;
      }
      wordBoxes.add(new OcrBox(text.replace(START_HL, startHlTag)
                                   .replace(END_HL, endHlTag),
                               pageId,  x, y, x + w, y + h, inHighlight));

      if (inHighlight && subsType != null) {
        if (subsType.equals("HypPart1") && attribs.get("SUBS_CONTENT").contains(END_HL)) {
          highlightHyphenEnd = true;
        } else if (highlightHyphenEnd){
          highlightHyphenEnd = false;
          inHighlight = false;
        }
      } else if (text.contains(END_HL)) {
        inHighlight = false;
      } else if (ocrFragment.substring(m.end(), Math.min(m.end() + END_HL.length(), ocrFragment.length())).equals(END_HL)) {
        inHighlight = false;
      }
    }
    return wordBoxes;
  }
}
