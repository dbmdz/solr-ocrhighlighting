package de.digitalcollections.solrocr.formats.hocr;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import de.digitalcollections.solrocr.formats.OcrParser;
import de.digitalcollections.solrocr.iter.BreakLocator;
import de.digitalcollections.solrocr.iter.IterableCharSequence;
import de.digitalcollections.solrocr.model.OcrBlock;
import de.digitalcollections.solrocr.model.OcrFormat;
import de.digitalcollections.solrocr.model.OcrPage;
import java.awt.Dimension;
import java.io.Reader;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.xml.stream.XMLStreamException;
import org.apache.commons.lang3.StringUtils;

public class HocrFormat implements OcrFormat {
  private static final Pattern pageIdPat = Pattern.compile(
      "(?:id=['\"](?<id>.+?)['\"]|x_source (?<source>.+?)['\";]|ppageno (?<pageno>\\d+))");
  private static final Pattern pageBboxPat = Pattern.compile("bbox 0 0 (?<width>\\d+) (?<height>\\d+)");
  private static final Pattern pageElemPat = Pattern.compile("<div.+?class=['\"]ocr_page['\"]\\s*(?<attribs>.+?)>");
  private static final Map<OcrBlock, Set<String>> blockClassMapping = ImmutableMap.<OcrBlock, Set<String>>builder()
      .put(OcrBlock.PAGE, ImmutableSet.of("ocr_page"))
      .put(OcrBlock.BLOCK, ImmutableSet.of("ocr_carea", "ocrx_block"))
      .put(OcrBlock.SECTION, ImmutableSet.of("ocr_chapter", "ocr_section", "ocr_subsection", "ocr_subsubsection"))
      .put(OcrBlock.PARAGRAPH, ImmutableSet.of("ocr_par"))
      .put(OcrBlock.LINE, ImmutableSet.of("ocr_line", "ocrx_line"))
      .put(OcrBlock.WORD, ImmutableSet.of("ocrx_word"))
      .build();

  @Override
  public BreakLocator getBreakLocator(IterableCharSequence text, OcrBlock... blockTypes) {
    List<String> breakClasses = Arrays.stream(blockTypes)
          .flatMap(b -> blockClassMapping.get(b).stream())
          .collect(Collectors.toList());
    return new HocrClassBreakLocator(text, breakClasses);
  }

  @Override
  public OcrParser getParser(Reader input, OcrParser.ParsingFeature... features) {
    try {
      return new HocrParser(input, features);
    } catch (XMLStreamException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public OcrPage parsePageFragment(String pageFragment) {
    // TODO: Might be faster without regexes? Profile!
    Matcher m = pageElemPat.matcher(pageFragment);
    if (m.find()) {
      return parsePage(m.group("attribs"));
    }
    return null;
  }

  // TODO: Might be faster without regexes? Profile!
  private OcrPage parsePage(String pageAttribs) {
    RuntimeException noPageIdExc = new RuntimeException("Pages must have an identifier, check your source files!");
    if (pageAttribs == null) {
      throw noPageIdExc;
    }

    Matcher idMatch = pageIdPat.matcher(pageAttribs);
    String pageId = null;
    while (idMatch.find()) {
      String candidate = Stream.of("id", "source", "pageno")
          .map(idMatch::group)
          .filter(StringUtils::isNotEmpty)
          .findFirst().orElseThrow(() -> noPageIdExc);
      if (candidate.equals(idMatch.group("id"))) {
        // A specific id is the ideal case, no need to check for further candidates
        pageId = candidate;
        break;
      } else if (candidate.equals(idMatch.group("source"))) {
        // A specific source is better than just a page number
        pageId = candidate;
      } else if (candidate.equals(idMatch.group("pageno")) && pageId == null) {
        // Only use a page number if no better candidate was found before
        pageId = candidate;
      }
    }
    if (pageId == null) {
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
  public boolean hasFormat(String ocrChunk) {
    return blockClassMapping.values().stream()
        .flatMap(Collection::stream)
        .anyMatch(ocrChunk::contains);
  }

  @Override
  public int getLastContentStartIdx(String content) {
    return content.lastIndexOf(">") + 1;
  }

  @Override
  public int getFirstContentEndIdx(String content) {
    return content.indexOf("</");
  }
}
