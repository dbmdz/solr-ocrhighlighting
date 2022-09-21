package com.github.dbmdz.solrocr.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.nio.charset.StandardCharsets;
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

  /** Providing empty pointer String yields Exception */
  @Test
  public void testEmptyToStringThrowsException() {
    // arrange
    String pointerStr = "";

    // act + assert
    assertThrows(RuntimeException.class, () -> SourcePointer.parse(pointerStr));
  }

  /**
   * Provided meaningful source information but no files acutally present
   *
   * @throws Exception
   */
  @Test
  public void testMeaningfullStringToStringButFilesMissing() throws Exception {
    // arrange
    Path p = Paths.get("src/test/resources/data/b0361249-3be2-4cf7-b90c-5eb888fbeb2b.ocr_text");
    String pointerStr = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);

    // act + assert
    assertThrows(RuntimeException.class, () -> SourcePointer.parse(pointerStr));
  }

  /**
   * Provided valid
   * sources @TempDir"assetstoreOcr/16/96/46/169646058196561338046488667538472128959" @TempDir"assetstoreOcr/70/24/82/70248214232052142117595264918632243561"
   *
   * @throws Exception
   */
  @Test
  public void testTwoValidFileSources() throws Exception {
    // arrange
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

    // act
    SourcePointer sourcePointer = SourcePointer.parse(pointerStr);
    String pointerString = sourcePointer.toString();

    // assert
    String expected = String.format("%s", pointerStr);
    assertEquals(expected, pointerString);
  }

  /**
   * One invalid local path results in an exception for the whole bunch
   *
   * @throws Exception
   */
  @Test
  public void testInvalidFileSources() throws Exception {
    // arrange
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
    Path fileMissing = dirTwo.resolve("invalid_path");
    String pointerStr = String.format("%s+%s+%s", fileOne, fileTwo, fileMissing);

    // act + assert
    assertThrows(RuntimeException.class, () -> SourcePointer.parse(pointerStr));
  }
}
