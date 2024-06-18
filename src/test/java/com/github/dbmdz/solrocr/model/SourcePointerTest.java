package com.github.dbmdz.solrocr.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Specification for {@link SourcePointer}
 *
 * @author u.hartwig
 */
public class SourcePointerTest {

  /** Required due "Method testSingleFileSourcePointerToString should have no parameters" */
  @Rule public TemporaryFolder tempDir = new TemporaryFolder();

  /** Provided valid sources in temporary directory. */
  @Test
  public void testTwoValidFileSources() throws Exception {
    Path someAlto =
        Paths.get("src/test/resources/data/alto_semantics_urn+nbn+de+gbv+3+5-83179_00000007.xml");
    Path dirOne = tempDir.newFolder("assetstoreOcr/16/96/46").toPath();
    Files.createDirectories(dirOne);
    Path fileOne = dirOne.resolve("169646058196561338046488667538472128959");
    Files.createFile(fileOne);
    Files.copy(someAlto, fileOne, StandardCopyOption.REPLACE_EXISTING);
    Path dirTwo = tempDir.newFolder("assetstoreOcr/70/24/82").toPath();
    Files.createDirectories(dirTwo);
    Path fileTwo = dirTwo.resolve("70248214232052142117595264918632243561");
    Files.copy(someAlto, fileTwo, StandardCopyOption.REPLACE_EXISTING);
    String pointerStr = String.format("%s+%s", fileOne, fileTwo);

    SourcePointer sourcePointer = SourcePointer.parse(pointerStr);

    assertNotNull(sourcePointer);
    assertEquals(pointerStr, sourcePointer.toString());
  }
}
