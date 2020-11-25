package de.digitalcollections.solrocr.lucene;

import com.ctc.wstx.api.WstxInputProperties;
import com.ctc.wstx.stax.WstxInputFactory;
import de.digitalcollections.solrocr.lucene.filters.OcrCharFilter;
import de.digitalcollections.solrocr.lucene.filters.OcrCharFilterFactory;
import de.digitalcollections.solrocr.lucene.filters.SanitizingXmlFilter;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import org.apache.commons.io.IOUtils;
import org.apache.lucene.analysis.CharFilter;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class SanitizingXmlFilterTest {
  private static final WstxInputFactory xmlInputFactory = new WstxInputFactory();
  static {
    xmlInputFactory.getConfig()
        .setInputParsingMode(WstxInputProperties.PARSING_MODE_DOCUMENTS);
    xmlInputFactory.getConfig()
        .doSupportDTDs(false);
  }

  @Test
  public void sanityTest() throws IOException {
    String brokenXml = "<b><c>hello</c></b></a><a><b><c>servus</c>";
    CharFilter filter = new SanitizingXmlFilter(new StringReader(brokenXml));
    String filtered = IOUtils.toString(filter);
    assertThat(filtered).isEqualTo("<b><c>hello</c></b>    <a><b><c>servus</c></b></a>");
  }

  @Test
  public void testWellformedXMLDoesntChange() throws IOException {
    Path p = Paths.get("src/test/resources/data/alto_nospace.xml");
    String alto = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    CharFilter filter = new SanitizingXmlFilter(new InputStreamReader(new FileInputStream(p.toFile())));
    String filtered = IOUtils.toString(filter);
    assertThat(filtered).isEqualTo(alto);
  }

  @Test
  public void testAltoPageCrossing() throws IOException {
    Path p = Paths.get("src/test/resources/data/alto.xml");
    String alto = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    CharFilter filter = new SanitizingXmlFilter(new StringReader(alto.substring(99762, 100508)));
    String filtered = IOUtils.toString(filter);
    assertThat(isWellFormed(filtered)).isTrue();
  }

  @Test
  public void testCharFilterCompatibility() throws IOException {
    Path p = Paths.get("src/test/resources/data/chronicling_america.xml");
    String alto = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    OcrCharFilterFactory ocrFac = new OcrCharFilterFactory(new HashMap<>());
    OcrCharFilter ocrFilter = (OcrCharFilter) ocrFac.create(new StringReader(alto));
    String filtered = IOUtils.toString(ocrFilter);
    assertThat(filtered).isNotEmpty();
  }

  boolean isWellFormed(String xml) {
    try {
      XMLStreamReader reader = xmlInputFactory.createXMLStreamReader(new StringReader(xml));
      while (reader.hasNext()) {
        reader.next();
      }
    } catch (XMLStreamException e) {
      return false;
    }
    return true;
  }
}
