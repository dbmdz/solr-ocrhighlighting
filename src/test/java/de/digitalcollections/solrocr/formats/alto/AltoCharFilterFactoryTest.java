package de.digitalcollections.solrocr.formats.alto;

import com.google.common.io.CharStreams;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class AltoCharFilterFactoryTest {
  final Path altoPath = Paths.get("src/test/resources/data/alto_utf8.xml");
  final Path hyphenPath = Paths.get("src/test/resources/data/bnl_lunion_1865-04-15.xml");
  final AltoCharFilterFactory fac = new AltoCharFilterFactory(new HashMap<>());

  @Test
  public void stripsDescription() throws IOException {
    Reader reader = fac.create(new InputStreamReader(new FileInputStream(altoPath.toFile())));
    String s = CharStreams.toString(reader);
    assertThat(s).doesNotContain("ABBYY");
  }

  @Test
  public void extractsCorrectText() throws IOException {
    Reader reader = fac.create(new InputStreamReader(new FileInputStream(altoPath.toFile())));
    String s = CharStreams.toString(reader).replaceAll("[\n\r]+", " ");
    assertThat(s).doesNotContain("<String");
    assertThat(s).doesNotContain("/>");
    assertThat(s).contains("forinden kan erfare");
  }

  @Test
  public void resolvesHyphenation() throws IOException {
    Reader reader = fac.create(new InputStreamReader(new FileInputStream(hyphenPath.toFile())));
    String s = CharStreams.toString(reader).replaceAll("\\s+", " ");
    assertThat(s).contains("avec lequel elle murmura :");
    assertThat(s).contains("mon vieil ami, faux espoir !");

  }
}
