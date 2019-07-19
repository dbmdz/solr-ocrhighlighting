package de.digitalcollections.solrocr.lucene;


import com.google.common.io.CharStreams;
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
import org.apache.commons.lang3.ArrayUtils;
import org.apache.lucene.analysis.CharFilter;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class ExternalUtf8ContentFilterTest {
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
    CharFilter filter = new Utf8MappingCharFilter(new BufferedReader(new FileReader(p.toFile())));
    String filtered = CharStreams.toString(filter);
    assertThat(filtered).isNotEmpty();
  }

  @Test
  public void extractRegion() throws IOException {
    Path p = Paths.get("src/test/resources/data/hocr.html");
    String fieldValue = p.toString() + "[3033216:3066308]";
    byte[] fileBytes = Files.readAllBytes(p);
    String fullFile = new String(fileBytes, StandardCharsets.UTF_8);
    String subRegion = new String(
        ArrayUtils.subarray(fileBytes, 3033216, 3066308),
        StandardCharsets.UTF_8);
    ExternalUtf8ContentFilterFactory fac = new ExternalUtf8ContentFilterFactory(new HashMap<>());
    Utf8RegionMappingCharFilter filter = (Utf8RegionMappingCharFilter) fac.create(new StringReader(fieldValue));
    String filtered = CharStreams.toString(filter);
    assertThat(filtered).isEqualTo(subRegion);
    assertThat(filter.correctOffset(subRegion.indexOf("id='page_108'"))).isEqualTo(3033238);
    assertThat(filter.correctOffset(subRegion.indexOf("Sonnenw"))).isEqualTo(3035824);
    assertThat(filter.correctOffset(subRegion.indexOf("id='word_108_252'"))).isEqualTo(3066094);
    assertThat(filtered).contains("<div class='ocr_page' id='page_108'");
    assertThat(filtered).doesNotContain("<div class='ocr_page' id='page_109'");
  }

}