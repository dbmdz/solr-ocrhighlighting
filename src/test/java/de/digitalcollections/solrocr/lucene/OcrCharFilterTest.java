package de.digitalcollections.solrocr.lucene;

import de.digitalcollections.solrocr.lucene.filters.ExternalUtf8ContentFilterFactory;
import de.digitalcollections.solrocr.lucene.filters.OcrCharFilter;
import de.digitalcollections.solrocr.lucene.filters.OcrCharFilterFactory;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import org.apache.commons.io.IOUtils;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class OcrCharFilterTest {
  private static final ExternalUtf8ContentFilterFactory filterFac = new ExternalUtf8ContentFilterFactory(
      new HashMap<>());
  private static final OcrCharFilterFactory ocrFac = new OcrCharFilterFactory(
      Collections.singletonMap("expandAlternatives", "true"));

  @Test
  public void testWithDehyphenationOffsets() throws IOException {
    Path p = Paths.get("src/test/resources/data/bnl_lunion_1865-04-15.xml");
    OcrCharFilter filter = (OcrCharFilter) ocrFac.create(filterFac.create(new StringReader(p.toString())));
    String doc = IOUtils.toString(filter);
    assertThat(doc).contains("Luimême avait renoncé à toute autre société que la mienne.");
    assertThat(filter.correctOffset(doc.indexOf("Luimême avait renoncé"))).isEqualTo(46844);
  }

  @Test
  public void testWithoutDehyphenationOffsetsAndAlternatives() throws IOException {
    Path p = Paths.get("src/test/resources/data/hocr.html");
    OcrCharFilter filter = (OcrCharFilter) ocrFac.create(filterFac.create(new StringReader(p.toString())));
    String doc = IOUtils.toString(filter);
    assertThat(doc).contains("3 Natlianiel\u2060\u2060Nathanael Brush");
    assertThat(filter.correctOffset(doc.indexOf("Natlianiel"))).isEqualTo(5385345);
    assertThat(filter.correctOffset(doc.indexOf("Nathanael Brush"))).isEqualTo(5385371);
    assertThat(doc).contains("before God's kingdom come, preferring temporal benefits before heavenly blessings");
    assertThat(filter.correctOffset(doc.indexOf("preferring temporal"))).isEqualTo(5427356);
    assertThat(filter.correctOffset(doc.indexOf("blessings"))).isEqualTo(5427838);
  }

  @Test
  public void testWithAlternatives() throws IOException {
    Path p = Paths.get("src/test/resources/data/chronicling_america.xml");
    OcrCharFilter filter = (OcrCharFilter) ocrFac.create(filterFac.create(new StringReader(p.toString())));
    String doc = IOUtils.toString(filter);
    assertThat(doc).contains(
        "Mr YoB\u2060\u2060OB Greene purchased\u2060\u2060purebased\u2060\u2060pUlcohased\u2060\u2060purebred of Ben");
    assertThat(filter.correctOffset(doc.indexOf("Mr YoB"))).isEqualTo(28909);
    assertThat(filter.correctOffset(doc.indexOf("OB Greene"))).isEqualTo(29100);
    assertThat(filter.correctOffset(doc.indexOf("purebased⁠⁠pUlcohased"))).isEqualTo(29489);
    assertThat(filter.correctOffset(doc.indexOf("of Ben"))).isEqualTo(29729);
  }

  @Test
  public void testWithNoExplicitSpaces() throws IOException {
    Path p = Paths.get("src/test/resources/data/alto_nospace.xml");
    OcrCharFilter filter = (OcrCharFilter) ocrFac.create(filterFac.create(new StringReader(p.toString())));
    String doc = IOUtils.toString(filter);
    assertThat(doc).contains("mit Augen ſahen, in welcher Zittau");
  }
}
