package de.digitalcollections.solrocr.util;

import de.digitalcollections.solrocr.reader.PeekingReader;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.apache.commons.io.IOUtils;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PeekingReaderTest {

  @Test
  public void testEquivalence() throws IOException {
    Path sourcePath = Paths.get("src/test/resources/data/alto.xml");
    FileReader baseReader = new FileReader(sourcePath.toFile());
    PeekingReader peekingReader = new PeekingReader(new FileReader(sourcePath.toFile()), 2048, 16384);
    String fromBase = IOUtils.toString(baseReader);
    String fromPeek = IOUtils.toString(peekingReader);
    assertThat(fromPeek).isEqualTo(fromBase);
  }

}