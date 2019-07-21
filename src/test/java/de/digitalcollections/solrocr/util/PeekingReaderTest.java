package de.digitalcollections.solrocr.util;

import com.google.common.io.CharStreams;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PeekingReaderTest {

  @Test
  public void testEquivalence() throws IOException {
    Path sourcePath = Paths.get("src/test/resources/data/alto.xml");
    FileReader baseReader = new FileReader(sourcePath.toFile());
    PeekingReader peekingReader = new PeekingReader(new FileReader(sourcePath.toFile()), 2048);
    String fromBase = CharStreams.toString(baseReader);
    String fromPeek = CharStreams.toString(peekingReader);
    assertThat(fromPeek).isEqualTo(fromBase);
  }

}