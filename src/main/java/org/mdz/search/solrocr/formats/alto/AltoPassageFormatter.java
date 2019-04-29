package org.mdz.search.solrocr.formats.alto;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.text.StringEscapeUtils;
import org.mdz.search.solrocr.formats.OcrPassageFormatter;
import org.mdz.search.solrocr.util.IterableCharSequence;
import org.mdz.search.solrocr.util.OcrBox;
import org.mdz.search.solrocr.util.TagBreakIterator;

public class AltoPassageFormatter extends OcrPassageFormatter {

  private final static String START_HL = "@@STARTHLTAG@@";
  private final static String END_HL = "@@ENDHLTAG@@";
  private final static Pattern pagePat = Pattern.compile("<Page ?(?<attribs>.+?)/?>");
  private final static Pattern wordPat = Pattern.compile("<String ?(?<attribs>.+?)/?>");
  private final static Pattern attribPat = Pattern.compile("(?<key>[A-Z]+?)=\"(?<val>.+?)\"");

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
    return StringEscapeUtils.unescapeXml(sb.toString().replaceAll("</?[A-Z]?.*?>", ""))
        .trim()
        .replaceAll(START_HL, startHlTag)
        .replaceAll(END_HL, endHlTag);
  }

  @Override
  protected List<OcrBox> parseWords(String ocrFragment) {
    // NOTE: We have to replace the original start/end highlight tags with non-XML placeholders so we don't break
    //       our rudimentary XML "parsing" to get to the attributes
    ocrFragment = ocrFragment
        .replaceAll(startHlTag, START_HL)
        .replaceAll(endHlTag, END_HL);
    List<OcrBox> wordBoxes = new ArrayList<>();
    Matcher m = wordPat.matcher(ocrFragment);
    boolean inHighlight = false;
    while (m.find()) {
      Map<String, String> attribs = parseAttribs(m.group("attribs"));
      int x = Integer.parseInt(attribs.get("HPOS"));
      int y = Integer.parseInt(attribs.get("VPOS"));
      int w = Integer.parseInt(attribs.get("WIDTH"));
      int h = Integer.parseInt(attribs.get("HEIGHT"));
      String text = attribs.get("CONTENT");
      if (text.contains(START_HL)) {
        inHighlight = true;
      }
      wordBoxes.add(new OcrBox(text.replace(START_HL, "")
                                   .replace(END_HL, ""),
                               x, y, x + w, y + h, inHighlight));
      boolean endOfHl = (
          text.contains(END_HL)
          || ocrFragment.substring(m.end(), Math.min(m.end() + END_HL.length(), ocrFragment.length())).equals(END_HL));
      if (endOfHl) {
        inHighlight = false;
      }
    }
    return wordBoxes;
  }
}
