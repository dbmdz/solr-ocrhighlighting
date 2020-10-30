package de.digitalcollections.solrocr.formats.alto;

import de.digitalcollections.solrocr.reader.PeekingReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.apache.commons.io.IOUtils;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class AltoCharFilterTest {
  final Path altoPath = Paths.get("src/test/resources/data/alto.xml");
  final Path hyphenPath = Paths.get("src/test/resources/data/bnl_lunion_1865-04-15.xml");

  @Test
  public void stripsDescription() throws IOException {
    Reader reader = new AltoCharFilter(new PeekingReader(new InputStreamReader(
        new FileInputStream(altoPath.toFile())), 2048, 8192));
    String s = IOUtils.toString(reader);
    assertThat(s).doesNotContain("ABBYY");
  }

  @Test
  public void extractsCorrectText() throws IOException {
    Reader reader = new AltoCharFilter(new PeekingReader(new InputStreamReader(
        new FileInputStream(altoPath.toFile())), 2048, 8192));
    String s = IOUtils.toString(reader).replaceAll("[\n\r]+", " ");
    assertThat(s).doesNotContain("<String");
    assertThat(s).doesNotContain("/>");
    assertThat(s).contains("forinden kan erfare");
  }

  @Test
  public void resolvesHyphenation() throws IOException {
    Reader reader = new AltoCharFilter(new PeekingReader(new InputStreamReader(
        new FileInputStream(hyphenPath.toFile())), 2048, 8192));
    String s = IOUtils.toString(reader).replaceAll("\\s+", " ");
    assertThat(s).contains("avec lequel elle murmura :");
    assertThat(s).contains("mon vieil ami, faux espoir !");
  }
}
