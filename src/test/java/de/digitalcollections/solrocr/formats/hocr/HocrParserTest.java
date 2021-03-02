package de.digitalcollections.solrocr.formats.hocr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.digitalcollections.solrocr.formats.OcrParser;
import de.digitalcollections.solrocr.lucene.filters.ExternalUtf8ContentFilterFactory;
import de.digitalcollections.solrocr.model.OcrBox;
import de.digitalcollections.solrocr.model.OcrPage;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.xml.stream.XMLStreamException;
import org.apache.lucene.analysis.CharFilter;
import org.junit.jupiter.api.Test;

public class HocrParserTest {
  private static final ExternalUtf8ContentFilterFactory filterFac = new ExternalUtf8ContentFilterFactory(new HashMap<>());

  public static String boxesToString(Iterable<OcrBox> boxes) {
    StringBuilder sb = new StringBuilder();
    boxes.forEach(b -> {
      if (b.isHyphenated()) {
        if (b.isHyphenStart()) {
          sb.append(b.getDehyphenatedForm());
        }
      } else {
        sb.append(b.getText());
      }
      if (b.getTrailingChars() != null) {
        sb.append(b.getTrailingChars());
      }
    });
    return sb.toString();
  }

  @Test
  public void testParseWithAlternatives() throws XMLStreamException {
    Path p = Paths.get("src/test/resources/data/hocr.html");
    CharFilter input = (CharFilter) filterFac.create(new StringReader(p.toString()));
    OcrParser parser = new HocrParser(input);
    List<OcrBox> boxes = parser.stream().collect(Collectors.toList());
    String text = boxesToString(boxes);
    assertThat(text).contains("on the Syth of October, which was followed by so sudden and severe a gale,");
    assertThat(text).contains("Kunnpfer describes the coast of Japan");
    assertThat(text).contains("S.W., expecting to fall");
    assertThat(text).contains("nämlich erſt in einem <arakteriſtiſchen Nachtcafe des Quartier latin");
    assertThat(boxes).hasSize(40946);
    List<OcrPage> pages = boxes.stream().map(OcrBox::getPage).distinct().collect(Collectors.toList());
    assertThat(pages).hasSize(187);
    assertThat(boxes).allMatch(b -> b.getPage() != null);
    assertThat(boxes.get(27187).getPage().id).isEqualTo("page_127");
    List<OcrBox> withAlternatives = boxes.stream().filter(b -> !b.getAlternatives().isEmpty()).collect(Collectors.toList());
    assertThat(withAlternatives).hasSize(2);
    assertThat(withAlternatives.get(0))
        .hasFieldOrPropertyWithValue("ulx", 1176.0f)
        .hasFieldOrPropertyWithValue("uly", 1569.0f)
        .hasFieldOrPropertyWithValue("lrx", 1532.0f)
        .hasFieldOrPropertyWithValue("lry", 1622.0f);
    assertThat(withAlternatives.get(0).getText()).isEqualTo("<arakteriſtiſchen");
    assertThat(input.correctOffset(withAlternatives.get(0).getTextOffset())).isEqualTo(72486);
    assertThat(withAlternatives.get(0).getAlternatives()).containsExactly("karakteriſtiſchen", "charakteriſtiſchen");
    assertThat(withAlternatives.get(0).getAlternativeOffsets().stream().map(input::correctOffset))
        .containsExactly(72530, 72571);
    assertThat(withAlternatives.get(1).getText()).isEqualTo("Natlianiel");
    assertThat(input.correctOffset(withAlternatives.get(1).getTextOffset())).isEqualTo(5385345);
    assertThat(withAlternatives.get(1).getAlternatives()).containsExactly("Nathanael");
    assertThat(withAlternatives.get(1).getAlternativeOffsets().stream().map(input::correctOffset))
        .containsExactly(5385371);
    List<OcrBox> hyphenated = boxes.stream().filter(OcrBox::isHyphenated).collect(Collectors.toList());
    assertThat(hyphenated).hasSize(32);
    assertThat(hyphenated.get(28).getText()).isEqualTo("pre");
    assertThat(hyphenated.get(28).getDehyphenatedForm()).isEqualTo("preferring");
    assertThat(hyphenated.get(29).getText()).isEqualTo("ferring");
    assertThat(hyphenated.get(29).getDehyphenatedForm()).isEqualTo("preferring");
  }

  @Test
  public void testParseWithHyphenation() throws XMLStreamException {
    Path p = Paths.get("src/test/resources/data/multicolumn.hocr");
    CharFilter input = (CharFilter) filterFac.create(new StringReader(p.toString()));
    OcrParser parser = new HocrParser(input);
    List<OcrBox> boxes = parser.stream().collect(Collectors.toList());
    assertThat(boxes).hasSize(811);
    String text = boxesToString(boxes);
    assertThat(text).contains("v. Schellerer; Ingenieur");
    assertThat(text).contains("Verfügung als adminiſtrativer Vorſtand");
    assertThat(boxes.get(706).getText()).isEqualTo("adminiſtra");
    assertThat(boxes.get(706).getDehyphenatedForm()).isEqualTo("adminiſtrativer");
    assertThat(boxes.get(707).getText()).isEqualTo("tiver");
    assertThat(boxes.get(707).getDehyphenatedForm()).isEqualTo("adminiſtrativer");
    assertThat(boxes.get(777).getDehyphenatedForm()).isEqualTo("Schellerer;");
    assertThat(input.correctOffset(boxes.get(706).getTextOffset())).isEqualTo(85892);
    List<OcrBox> hyphenated = boxes.stream().filter(OcrBox::isHyphenated).collect(Collectors.toList());
    assertThat(hyphenated).hasSize(4);
  }

  @Test
  public void testHighlightedFragmentParse() throws XMLStreamException, IOException {
    Path p = Paths.get("src/test/resources/data/hocr.html");
    StringBuilder fragment = new StringBuilder(
        new String(Files.readAllBytes(p), StandardCharsets.UTF_8).substring(1723430, 1730016));
    fragment.insert(2942, OcrParser.START_HL);
    fragment.insert(3365 + OcrParser.START_HL.length(), OcrParser.END_HL);
    OcrParser parser = new HocrParser(new StringReader(fragment.toString()));
    List<OcrBox> boxes = parser.stream().collect(Collectors.toList());
    assertThat(boxes.get(20).getHighlightSpan()).isNull();
    UUID hlSpan = boxes.get(21).getHighlightSpan();
    assertThat(hlSpan).isNotNull();
    assertThat(boxes.get(22).getHighlightSpan()).isEqualTo(hlSpan);
    assertThat(boxes.get(23).getHighlightSpan()).isEqualTo(hlSpan);
    assertThat(boxes.get(24).getHighlightSpan()).isEqualTo(hlSpan);
    assertThat(boxes.get(25).getHighlightSpan()).isEqualTo(hlSpan);
    assertThat(boxes.get(26).getHighlightSpan()).isNull();
    assertThat(boxes).isNotEmpty();
  }

  @Test
  public void testPartialDocument() throws XMLStreamException {
    String fragment = "<span class=\"ocr_line\" title=\"bbox 245 1992 1312 2038\"><span class=\"ocrx_word\" title=\"bbox 245 1996 356 2038\">object</span> <span class=\"ocrx_word\" title=\"bbox 370 2003 426 2026\">too</span> <span class=\"ocrx_word\" title=\"bbox 441 1993 568 2035\">hastily,</span> <span class=\"ocrx_word\" title=\"bbox 584 1993 616 2024\">in</span> <span class=\"ocrx_word\" title=\"bbox 632 1992 778 2024\">addition</span> <span class=\"ocrx_word\" title=\"bbox 795 1998 830 2024\">to</span> <span class=\"ocrx_word\" title=\"bbox 847 1993 901 2025\">the</span> <span class=\"ocrx_word\" title=\"bbox 917 1994 1000 2026\">facts</span> <span class=\"ocrx_word\" title=\"bbox 1016 1993 1146 2038\">already</span> <span class=\"ocrx_word\" title=\"bbox 1162 1997 1268 2028\">stated</span> <span class=\"ocrx_word\" title=\"bbox 1286 1997 1312 2027\">it</span> </span><span class=\"ocr_line\" title=\"bbox 244 2035 1316 2083\"><span class=\"ocrx_word\" title=\"bbox 244 2040 348 2083\">ought</span> <span class=\"ocrx_word\" title=\"bbox 363 2045 396 2070\">to</span> <span class=\"ocrx_word\" title=\"bbox 412 2037 453 2069\">be</span> <span class=\"ocrx_word\" title=\"bbox 468 2036 647 2076\">remarked,</span> <span class=\"ocrx_word\" title=\"bbox 660 2035 731 2069\">that</span> <span class=\"ocrx_word\" title=\"bbox 744 2035 911 2080\">Kunnpfer</span> <span class=\"ocrx_word\" title=\"bbox 924 2037 1087 2071\">describes</span> <span class=\"ocrx_word\" title=\"bbox 1105 2039 1161 2072\">the</span> <span class=\"ocrx_word\" title=\"bbox 1174 2045 1265 2072\">coast</span> <span class=\"ocrx_word\" title=\"bbox 1276 2040 1316 2070\">of</span></span>    \n" +
        "\n" +
        "<p class=\"ocr_par\" title=\"bbox 1206 2083 1314 2125\" style=\"font-size:11pt;font-family:&quot;Times&quot;;font-style:normal\"><span class=\"ocr_line\" title=\"bbox 1206 2083 1314 2125\"><span class=\"ocrx_word\" title=\"bbox 1206 2083 1314 2125\">\uD83D\uDD25Japan\uD83E\uDDEF</span></span></p>\n" +
        "\n" +
        "      \n" +
        "\n" +
        "      \n" +
        "\n" +
        "\n" +
        "<div class=\"ocr_page\" id=\"page_249\" title=\"bbox 0 0 1428 2403\">\n" +
        "\n" +
        "</div>\n";
    HocrParser parser = new HocrParser(new StringReader(fragment), OcrParser.ParsingFeature.TEXT);
    List<OcrBox> boxes = parser.stream().collect(Collectors.toList());
    assertThat(boxes).isNotEmpty();
  }

  @Test
  public void testSpaceAtLineEnd() throws FileNotFoundException, XMLStreamException {
    HocrParser parser = new HocrParser(
        new FileReader(Paths.get("src/test/resources/data/space_after.html").toFile()),
        OcrParser.ParsingFeature.TEXT);
    List<OcrBox> boxes = parser.stream().collect(Collectors.toList());
    assertThat(boxes.stream().filter(b -> !b.isHyphenStart()))
        .allMatch(b -> b.getTrailingChars().contains(" "));
  }

  @Test
  public void testParsingErrorBeginningHasSource() throws XMLStreamException {
    Path p = Paths.get("src/test/resources/data/multicolumn.hocr");
    String ptr = p.toString() + "[5000:8000]";
    CharFilter input = (CharFilter) filterFac.create(new StringReader(ptr));
    assertThatThrownBy(() -> new HocrParser(input))
        .hasMessageContaining(ptr);
  }

  @Test
  public void testParsingErrorEndHasSource() throws XMLStreamException {
    Path p = Paths.get("src/test/resources/data/multicolumn.hocr");
    String ptr = p.toString() + "[3275:96251]";
    CharFilter input = (CharFilter) filterFac.create(new StringReader(ptr));
    OcrParser parser = new HocrParser(input);
    assertThatThrownBy(() -> parser.stream().count())
        .hasMessageContaining(ptr);
  }
}
