package de.digitalcollections.solrocr.formats.alto;

import de.digitalcollections.solrocr.formats.OcrParser;
import de.digitalcollections.solrocr.lucene.filters.ExternalUtf8ContentFilterFactory;
import de.digitalcollections.solrocr.model.OcrBox;
import de.digitalcollections.solrocr.model.OcrPage;
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
import org.assertj.core.data.Percentage;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class AltoParserTest {
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
  public void testParse() throws XMLStreamException {
    Path p = Paths.get("src/test/resources/data/chronicling_america.xml");
    CharFilter input = (CharFilter) filterFac.create(new StringReader(p.toString()));
    OcrParser parser = new AltoParser(input);
    List<OcrBox> boxes = parser.stream().collect(Collectors.toList());
    assertThat(boxes).hasSize(4430);
    assertThat(boxes.get(2294).getPage().id).isEqualTo("ID1");
    assertThat(boxes.get(2294).getPage().dimensions)
        .hasFieldOrPropertyWithValue("width", 20463.0)
        .hasFieldOrPropertyWithValue("height", 25941.0);
    OcrBox word = boxes.get(3912);
    assertThat(word.getText()).isEqualTo("Benton");
    assertThat(input.correctOffset(word.getTextOffset())).isEqualTo(771855);
    assertThat(word)
        .hasFieldOrPropertyWithValue("ulx", 18129.0f)
        .hasFieldOrPropertyWithValue("uly", 6546.0f)
        .hasFieldOrPropertyWithValue("lrx", 18552.0f)
        .hasFieldOrPropertyWithValue("lry", 6663.0f);
    OcrBox withAlts = boxes.get(4088);
    assertThat(withAlts.getText()).isEqualTo("Dunujn");
    assertThat(input.correctOffset(withAlts.getTextOffset())).isEqualTo(807152);
    assertThat(withAlts.getAlternatives()).containsExactly("Dn4n", "Unsung");
    assertThat(withAlts.getAlternativeOffsets().stream().map(input::correctOffset))
        .containsExactly(807189, 807220);
    assertThat(withAlts.getConfidence()).isCloseTo(0.825, Percentage.withPercentage(0.05));
    OcrBox hyphenStart = boxes.get(3735);
    assertThat(hyphenStart.getText()).isEqualTo("wel-");
    assertThat(hyphenStart.getDehyphenatedForm()).isEqualTo("welcome");
    assertThat(input.correctOffset(hyphenStart.getDehyphenatedOffset())).isEqualTo(736486);
    OcrBox hyphenEnd = boxes.get(3736);
    assertThat(hyphenEnd.getText()).isEqualTo("come");
    assertThat(hyphenEnd.getDehyphenatedForm()).isEqualTo("welcome");
    assertThat(input.correctOffset(hyphenEnd.getDehyphenatedOffset())).isEqualTo(736881);
  }

  @Test
  public void testParseText() throws XMLStreamException {
    Path p = Paths.get("src/test/resources/data/alto.xml");
    OcrParser parser = new AltoParser(filterFac.create(new StringReader(p.toString())));
    String text = boxesToString(parser);
    assertThat(text).contains("forinden kan erfare");
    assertThat(text).contains("T<lffadel>l>velse.");
    p = Paths.get("src/test/resources/data/alto_multi/1865-05-24_01-00002.xml");
    parser = new AltoParser(filterFac.create(new StringReader(p.toString())));
    text = boxesToString(parser);
    assertThat(text).contains("visage baigné de l.irmes");
    p = Paths.get("src/test/resources/data/bnl_lunion_1865-04-15.xml");
    parser = new AltoParser(filterFac.create(new StringReader(p.toString())));
    List<OcrBox> boxes = parser.stream().collect(Collectors.toList());
    text = boxesToString(boxes);
    assertThat(text).contains("avec lequel elle murmura :");
    assertThat(text).contains("mon vieil ami, faux espoir !");
  }

  @Test
  public void testHighlightedFragmentParse() throws XMLStreamException, IOException {
    Path p = Paths.get("src/test/resources/data/chronicling_america.xml");
    StringBuilder fragment = new StringBuilder(
        new String(Files.readAllBytes(p), StandardCharsets.UTF_8).substring(26773, 31997));
    fragment.insert(2327, OcrParser.START_HL);
    fragment.insert(3109 + OcrParser.START_HL.length(), OcrParser.END_HL);
    List<OcrBox> boxes = new AltoParser(new StringReader(fragment.toString())).stream().collect(Collectors.toList());
    assertThat(boxes.get(9).getHighlightSpan()).isNull();
    UUID hlSpan = boxes.get(10).getHighlightSpan();
    assertThat(hlSpan).isNotNull();
    assertThat(boxes.get(11).getHighlightSpan()).isEqualTo(hlSpan);
    assertThat(boxes.get(12).getHighlightSpan()).isEqualTo(hlSpan);
    assertThat(boxes.get(13).getHighlightSpan()).isEqualTo(hlSpan);
    assertThat(boxes.get(14).getHighlightSpan()).isEqualTo(hlSpan);
    assertThat(boxes.get(15).getHighlightSpan()).isNull();
  }

  @Test
  public void testMultiFileParse() throws XMLStreamException, IOException {
    String ptr = Files.list(Paths.get("src/test/resources/data/alto_multi"))
        .filter(p -> p.getFileName().toString().startsWith("1860-"))
        .map(Path::toAbsolutePath)
        .map(Path::toString)
        .collect(Collectors.joining("+"));
    List<OcrBox> boxes = new AltoParser(filterFac.create(new StringReader(ptr)))
        .stream().collect(Collectors.toList());
    List<OcrPage> pages = boxes.stream().map(OcrBox::getPage).distinct().collect(Collectors.toList());
    assertThat(pages).hasSize(4);
    assertThat(pages.get(0).dimensions)
        .hasFieldOrPropertyWithValue("width", 3170.0)
        .hasFieldOrPropertyWithValue("height", 4890.0);
  }

  @Test
  public void testWeirdParsingIssue() throws XMLStreamException {
    String ptr = Paths.get("src/test/resources/data/alto_nospace.xml").toAbsolutePath().toString();
    List<OcrBox> boxes = new AltoParser(filterFac.create(new StringReader(ptr))).stream().collect(Collectors.toList());
    assertThat(boxes).isNotEmpty();
  }
}
