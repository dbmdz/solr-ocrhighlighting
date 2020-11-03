package de.digitalcollections.solrocr.formats.hocr;

import de.digitalcollections.solrocr.reader.PeekingReader;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.apache.commons.io.IOUtils;
import org.apache.lucene.analysis.CharFilter;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class HocrCharFilterTest {
  private static CharFilter makeFilter(Path fixturePath, boolean expandAlternatives)
      throws FileNotFoundException {
    return new HocrCharFilter(
        new PeekingReader(new InputStreamReader(new FileInputStream(fixturePath.toFile())),
            2048, 8192),
        expandAlternatives);
  }

  @Test
  public void testExtractPlaintext() throws IOException {
    Reader filter = makeFilter(Paths.get("src/test/resources/data/hocr.html"), false);
    String s = IOUtils.toString(filter);
    assertThat(s).contains("Kunnpfer describes the coast of\nJapan");
  }

  @Test
  public void testResolvesHyphenation() throws IOException {
    Reader filter = makeFilter(Paths.get("src/test/resources/data/hocr.html"), false);
    String s = IOUtils.toString(filter);
    assertThat(s).contains("S.W., expecting to fall");
  }

  @Test
  public void testKeepsTrackOfOffsets() throws IOException {
    Path hocrPath = Paths.get("src/test/resources/data/hocr.html");
    String raw = new String(Files.readAllBytes(hocrPath), StandardCharsets.UTF_8);
    int rawIdx = raw.indexOf("Kunnpfer");
    CharFilter filter = makeFilter(hocrPath, false);
    String filtered = IOUtils.toString(filter);
    int filteredIdx = filtered.indexOf("Kunnpfer");
    assertThat(filter.correctOffset(filteredIdx)).isEqualTo(rawIdx);
  }

  @Test
  public void testIgnoresAlternatives() throws IOException {
    Reader filter = makeFilter(Paths.get("src/test/resources/data/hocr.html"), false);
    String s = IOUtils.toString(filter);
    // Whitespace isn't quite normalized, but this doesn't matter for the search use case
    //                |
    //                +---------------------------+-------------------+
    //                                            |                  ||
    //                                            v                  vv
    assertThat(s).contains("nämlich erſt in einem  <arakteriſtiſchen   Nachtcafe des Quartier latin");
  }

  @Test
  public void outputsAlternatives() throws IOException {
    Reader filter = makeFilter(Paths.get("src/test/resources/data/hocr.html"), true);
    String s = IOUtils.toString(filter);
    assertThat(s).contains(
        "einem  <arakteriſtiſchen\u2060\u2060karakteriſtiſchen\u2060\u2060charakteriſtiſchen   Nachtcafe");
  }
}
