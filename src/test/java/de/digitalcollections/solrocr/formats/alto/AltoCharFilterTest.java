package de.digitalcollections.solrocr.formats.alto;

import de.digitalcollections.solrocr.reader.PeekingReader;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.apache.commons.io.IOUtils;
import org.apache.lucene.analysis.CharFilter;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class AltoCharFilterTest {
  final Path altoPath = Paths.get("src/test/resources/data/alto.xml");
  final Path hyphenPath = Paths.get("src/test/resources/data/bnl_lunion_1865-04-15.xml");
  final Path decimalEscapedPath = Paths.get("src/test/resources/data/alto_multi/1865-05-24_01-00002.xml");
  final Path alternativePath = Paths.get("src/test/resources/data/chronicling_america.xml");

  private static CharFilter makeFilter(Path xmlPath, boolean expandAlternatives)
      throws FileNotFoundException {
    return new AltoCharFilter(
        new PeekingReader(new InputStreamReader(new FileInputStream(xmlPath.toFile())),
                          2048, 8192),
        expandAlternatives);
  }

  @Test
  public void stripsDescription() throws IOException {
    String s = IOUtils.toString(makeFilter(altoPath, false));
    assertThat(s).doesNotContain("ABBYY");
  }

  @Test
  public void extractsCorrectText() throws IOException {
    String s = IOUtils.toString(makeFilter(altoPath, false));
    assertThat(s).doesNotContain("<String");
    assertThat(s).doesNotContain("/>");
    assertThat(s).contains("forinden kan erfare");
  }

  @Test
  public void resolvesHyphenation() throws IOException {
    String s = IOUtils.toString(makeFilter(hyphenPath, false)).replaceAll("\\s+", " ");
    assertThat(s).contains("avec lequel elle murmura :");
    assertThat(s).contains("mon vieil ami, faux espoir !");
  }

  @Test
  public void resolvesEntityReferences() throws IOException {
    String s = IOUtils.toString(makeFilter(altoPath, false));
    assertThat(s).contains("T<lffadel>l>velse.");
  }

  @Test
  public void resolvesDecimalReferences() throws IOException {
    String s = IOUtils.toString(makeFilter(decimalEscapedPath, false));
    assertThat(s).contains("visage baigné de l.irmes");
  }

  @Test
  public void ignoresAlternatives() throws IOException {
    String s = IOUtils.toString(makeFilter(alternativePath, false));
    assertThat(s).contains("YoB Greene purchased of Ben F Mark 40 cattle average 3400 pounds");
  }

  @Test
  public void outputsAlternatives() throws IOException {
    String s = IOUtils.toString(makeFilter(alternativePath, true));
    assertThat(s).contains(
        "YoB\u2060\u2060OB Greene purchased\u2060\u2060"
        + "purebased\u2060\u2060pUlcohased\u2060\u2060purebred of Ben F Mark 40"
        + " cattle\u2060\u2060cattlc");
  }

  @Test
  public void keepsTrackOfOffsets() throws IOException {
    CharFilter filter = makeFilter(altoPath, false);
    String source = new String(Files.readAllBytes(altoPath), StandardCharsets.UTF_8);
    String parsed = IOUtils.toString(filter);
    int parsedIdx = parsed.indexOf("T<lffadel>l>velse.");
    int srcIdx = source.indexOf("T&lt;lffadel&gt;l&gt;velse.");
    assertThat(filter.correctOffset(parsedIdx)).isEqualTo(srcIdx);
  }
}
