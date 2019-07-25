package de.digitalcollections.solrocr.lucene;


import com.google.common.collect.ImmutableList;
import de.digitalcollections.solrocr.lucene.filters.ExternalUtf8ContentFilter;
import de.digitalcollections.solrocr.lucene.filters.ExternalUtf8ContentFilterFactory;
import de.digitalcollections.solrocr.util.MultiFileReader;
import de.digitalcollections.solrocr.util.SourcePointer;
import de.digitalcollections.solrocr.util.Utf8;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.stream.Collectors;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.lucene.analysis.CharFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class ExternalUtf8ContentFilterTest {
  private ExternalUtf8ContentFilterFactory fac;

  @BeforeEach
  public void setup() {
    fac = new ExternalUtf8ContentFilterFactory(new HashMap<>());
  }

  @Test
  public void testDecodedLengthCalculation() {
    String randomUnicode = "aaaaaaaaaaaaaaa"
         + "\uDB6C\uDE16+䝤즐˿혂\u139A\uDA4F\uDFAA`ҧĪ\u05EF\uDB7E\uDF45|\uDBA6\uDCB0륱7幰S5\uDAFD\uDF22뭗b"
         + "晓㈗I{䯞ʆ\uDA19\uDDACl씤\n"
         + "&0,\uDA19\uDF16ۦ\uDB03\uDC3EU탍蚳ԏ\uDBF2\uDECE҈\uD9EC\uDC14Q3փ\uD9E6\uDCEE\uD8CA\uDD09"
         + "\uDBEB\uDF31<ۥ⥘Ⱉ㢗Ѡ\uD921\uDEE8\uDA77\uDEF6㿸\uDAAF\uDCCB\uDA3D\uDF42ˡР\n"
         + "\uD934\uDDD6+ɖ\uD84D\uDFE9삶2\uDA25\uDD38͖ķ索\uDA43\uDF5Cڭp\uD834\uDE79\uDB5F\uDD56"
         + "ش\uD963\uDD0D㟢;\u09D0\uDAB0\uDF08x֗\uEEB4ȹ肇ǘ띻\uD925\uDFEFߥ۽\u0094\n"
         + "W\uDAE4\uDF92\uD8F4\uDC1Aڕ\uDBCC\uDF96\uD970\uDDEC\"翶Ļ\uDB75\uDFF2\uD80E\uDCAB\uDAA5"
         + "\uDF0BԼ\uDAC2\uDFE8ѥ\u0AFCi!<ʘ�肾\uDBA3\uDE9AʒኲQ䧲ˢZ膂\uDB8C\uDF01ǘ\n"
         + "z\uF485մټҘ»\uDB02\uDD15Ȧ\uDBAD\uDE69ԕа뿇1ꈂ\uD9A9\uDDEC\uD9E4\uDC11\uDA47\uDD1A᳦誳\uD8A9"
         + "\uDCF8k8ㅋݏ\uDB74\uDEE1杽ܷ\uDAA1\uDC4ELꢾ냃H";
    byte[] utf8Bytes = randomUnicode.getBytes(StandardCharsets.UTF_8);
    assertThat(Utf8.decodedLength(utf8Bytes)).isEqualTo(randomUnicode.length());
  }

  @Test
  public void extractFully() throws IOException {
    Path p = Paths.get("src/test/resources/data/hocr.html");
    CharFilter filter = new ExternalUtf8ContentFilter(
        new BufferedReader(new FileReader(p.toFile())),
        ImmutableList.of(new SourcePointer.Region(0, (int) p.toFile().length())));
    String full = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    String filtered = IOUtils.toString(filter);
    assertThat(filtered).isNotEmpty();
    assertThat(filter.correctOffset(full.indexOf("id='page_108'"))).isEqualTo(3033238);
    assertThat(filter.correctOffset(full.indexOf("Sonnenw"))).isEqualTo(3035824);
    assertThat(filter.correctOffset(full.indexOf("id='word_108_252'"))).isEqualTo(3066094);
  }

  @Test
  public void extractRegion() throws IOException {
    Path p = Paths.get("src/test/resources/data/hocr.html");
    String fieldValue = p.toString() + "[3033216:3066308]";
    byte[] fileBytes = Files.readAllBytes(p);
    String subRegion = new String(
        ArrayUtils.subarray(fileBytes, 3033216, 3066308),
        StandardCharsets.UTF_8);
    ExternalUtf8ContentFilter filter = (ExternalUtf8ContentFilter) fac.create(new StringReader(fieldValue));
    String filtered = IOUtils.toString(filter);
    assertThat(filtered).isEqualTo(subRegion);
    assertThat(filter.correctOffset(subRegion.indexOf("id='page_108'"))).isEqualTo(3033238);
    assertThat(filter.correctOffset(subRegion.indexOf("Sonnenw"))).isEqualTo(3035824);
    assertThat(filter.correctOffset(subRegion.indexOf("id='word_108_252'"))).isEqualTo(3066094);
    assertThat(filtered).contains("<div class='ocr_page' id='page_108'");
    assertThat(filtered).doesNotContain("<div class='ocr_page' id='page_109'");
  }

  @Test
  public void multipleFiles() throws IOException {
    Path basePath = Paths.get("src/test/resources/data/multi_txt");
    String ptr = Files.list(basePath)
        .filter(p -> p.getFileName().toString().startsWith("part_"))
        .map(p -> p.toAbsolutePath().toString())
        .sorted()
        .collect(Collectors.joining("+"));
    ExternalUtf8ContentFilter filter = (ExternalUtf8ContentFilter) fac.create(new StringReader(ptr));
    String filtered = IOUtils.toString(filter);
    assertThat(filtered).isEqualTo("ene mene mistäes rappelt in der kistäene mene mäckund du bist wäg");
    assertThat(filter.correctOffset(filtered.indexOf("mistäes"))).isEqualTo(9);
    assertThat(filter.correctOffset(filtered.indexOf("kistäene"))).isEqualTo(33);
    assertThat(filter.correctOffset(filtered.indexOf("wäg"))).isEqualTo(65);
  }

  @Test
  public void multipleFilesWithRegions() throws IOException {
    String ptr =
          "src/test/resources/data/multi_txt/part_1.txt[9:]"
        + "+src/test/resources/data/multi_txt/part_2.txt[3:10]"
        + "+src/test/resources/data/multi_txt/part_3.txt[:8]"
        + "+src/test/resources/data/multi_txt/part_4.txt[4:6,12:]";
    ExternalUtf8ContentFilter filter = (ExternalUtf8ContentFilter) fac.create(new StringReader(ptr));
    String filtered = IOUtils.toString(filter);
    assertThat(filtered).isEqualTo("mistärappeltene meneduwäg");
    assertThat(filter.correctOffset(filtered.indexOf("rappelt"))).isEqualTo(18);
    assertThat(filter.correctOffset(filtered.indexOf("ene mene"))).isEqualTo(39);
    assertThat(filter.correctOffset(filtered.indexOf("du"))).isEqualTo(57);
    assertThat(filter.correctOffset(filtered.indexOf("wäg"))).isEqualTo(65);
  }

  @Test
  public void multipleRegions() throws IOException {
    String ptr = "src/test/resources/data/multi_txt/complete.txt[4:8,18:25,33:39]";
    ExternalUtf8ContentFilter filter = (ExternalUtf8ContentFilter) fac.create(new StringReader(ptr));
    String filtered = IOUtils.toString(filter);
    assertThat(filtered).isEqualTo("menerappeltkistä");
    assertThat(filter.correctOffset(4)).isEqualTo(18);
    assertThat(filter.correctOffset(11)).isEqualTo(33);
  }

  @Test
  public void multipleLongerFiles() throws IOException {
    Path aPath = Paths.get("src/test/resources/data/alto_multi/1865-05-24_01-00001.xml");
    Path bPath = Paths.get("src/test/resources/data/alto_multi/1865-05-24_01-00002.xml");
    String aText = new String(Files.readAllBytes(aPath), StandardCharsets.UTF_8);
    String bText = new String(Files.readAllBytes(bPath), StandardCharsets.UTF_8);
    String fullText = aText + bText;
    String fullPtr = aPath.toString() + "+" + bPath.toString();
    try (ExternalUtf8ContentFilter filter = (ExternalUtf8ContentFilter) fac.create(new StringReader(fullPtr))) {
      String filtered = IOUtils.toString(filter);
      assertThat(filtered.length()).isEqualTo(fullText.length());
      assertThat(filtered).isEqualTo(fullText);
    }
  }

  @Test
  public void testMultiFileReader() throws IOException {
    Path aPath = Paths.get("src/test/resources/data/alto_multi/1865-05-24_01-00001.xml");
    Path bPath = Paths.get("src/test/resources/data/alto_multi/1865-05-24_01-00002.xml");
    try (MultiFileReader r = new MultiFileReader(ImmutableList.of(aPath, bPath))) {
      String fromReader = IOUtils.toString(r);
      String aText = new String(Files.readAllBytes(aPath), StandardCharsets.UTF_8);
      String bText = new String(Files.readAllBytes(bPath), StandardCharsets.UTF_8);
      String fromFiles = aText + bText;
      assertThat(fromReader).isEqualTo(fromFiles);
    }
  }
}