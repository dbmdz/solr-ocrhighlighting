package de.digitalcollections.solrocr.util;

import de.digitalcollections.solrocr.formats.hocr.HocrParser;
import de.digitalcollections.solrocr.lucene.filters.SanitizingXmlFilter;
import de.digitalcollections.solrocr.model.OcrBox;
import de.digitalcollections.solrocr.reader.PeekingReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;
import javax.xml.stream.XMLStreamException;
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

  @Test
  public void testPeekAndRead() throws IOException {
    String fragment = "<l><w x=\".0000 .4227 .0435 .0083\">rwähnen</w> <w x=\".0513 .4227 .0250 .0083\">einer</w> <w x=\".0838 .4227 .0407 .0083\">ſolchen</w>; <w x=\".1362 .4227 .0120 .0083\">ſie</w> <w x=\".1598 .4227 .0322 .0083\">ſpricht</w> <w x=\".1995 .4227 .0441 .0083\">geradezu</w> </l><l><w x=\".0007 .4312 .0188 .0085\">big</w>. <w x=\".0233 .4312 .0171 .0085\">Im</w> <w x=\".0441 .4312 .0613 .0085\">verfloſſenen</w> <w x=\".1081 .4312 .0298 .0085\">Jahre</w> <w x=\".1407 .4312 .0380 .0085\">wurden</w> <w x=\".1821 .4312 .0380 .0085\">Proben</w> <w x=\".2238 .4312 .0198 .0085\">von</w> </l><l><w x=\".0000 .4398 .0099 .0079\">us</w> <w x=\".0175 .4398 .0363 .0079\">Italien</w> <w x=\".0623 .4398 .0222 .0079\">nach</w> <w x=\".0921 .4398 .0472 .0079\">\uD83D\uDD25München\uD83E\uDDEF</w> <w x=\".1472 .4398 .0438 .0079\">geſchickt</w>, <w x=\".1988 .4398 .0168 .0079\">mit</w> <w x=\".2231 .4398 .0209 .0079\">dem</w> </l><l><w x=\".0003 .4481 .0257 .0085\">einen</w> <w x=\".0335 .4481 .0383 .0085\">Blätter</w> <w x=\".0794 .4481 .0222 .0085\">gute</w> <w x=\".1085 .4481 .0496 .0085\">Reſultate</w> <w x=\".1656 .4481 .0144 .0085\">bei</w> <w x=\".1879 .4481 .0154 .0085\">der</w> <w x=\".2105 .4481 .0329 .0085\">Fütte</w> </l><l><w x=\".0000 .4567 .0151 .0086\">upe</w> <w x=\".0264 .4567 .0424 .0086\">geliefert</w> <w x=\".0801 .4567 .0380 .0086\">hätten</w>, <w x=\".1290 .4567 .0144 .0086\">die</w> <w x=\".1506 .4567 .0400 .0086\">anderen</w> <w x=\".2019 .4567 .0418 .0086\">dagegen</w> </l>";
    PeekingReader reader = new PeekingReader(new StringReader(fragment), 2048, 16384);
    char[] out = new char[4128];
    int numRead = reader.read(out, 0, 128);
    assertThat(numRead).isEqualTo(128);
    assertThat(new String(out, 0, 128)).isEqualTo(fragment.substring(0, 128));
    numRead = reader.read(out, 128, 4000);
    assertThat(numRead).isEqualTo(fragment.length() - 128);
    assertThat(new String(out, 0, fragment.length())).isEqualTo(fragment);
  }

  @Test
  public void testBug() throws IOException, XMLStreamException {
    String fragment = "<span class='ocr_line' id='line_17_6' title=\"bbox 185 482 1492 539; baseline 0.003 -15; x_size 52; x_descenders 9; x_ascenders 13\">\n" +
        "      <span class='ocrx_word' id='word_17_32' title='bbox 185 482 312 534; x_wconf 96' lang='deu'>dafür.</span>\n" +
        "      <span class='ocrx_word' id='word_17_33' title='bbox 379 485 450 537; x_wconf 72'>I&lt;</span>\n" +
        "      <span class='ocrx_word' id='word_17_34' title='bbox 484 485 557 537; x_wconf 95'>hab</span>\n" +
        "      <span class='ocrx_word' id='word_17_35' title='bbox 602 494 642 529; x_wconf 96'>es</span>\n" +
        "      <span class='ocrx_word' id='word_17_36' title='bbox 685 487 878 529; x_wconf 96'>mitunter</span>\n" +
        "      <span class='ocrx_word' id='word_17_37' title='bbox 922 484 1010 537; x_wconf 22'>auch</span>\n" +
        "      <span class='ocrx_word' id='word_17_38' title='bbox 1054 484 1160 536; x_wconf 93'>ſchon</span>\n" +
        "      <span class='ocrx_word' id='word_17_39' title='bbox 1208 485 1388 539; x_wconf 93'>beſtätigt</span>\n" +
        "      <span class='ocrx_word' id='word_17_40' title='bbox 1431 499 1492 539; x_wconf 92'>ge-</span>\n" +
        "     </span>\n" +
        "     <span class='ocr_line' id='line_17_7' title=\"bbox 186 550 380 603; baseline 0.01 -10; x_size 53; x_descenders 8; x_ascenders 16\">\n" +
        "      <span class='ocrx_word' id='word_17_41' title='bbox 186 550 344 603; x_wconf 91' lang='Fraktur'>funden.</span>\n" +
        "      <span class='ocrx_word' id='word_17_42' title='bbox 358 550 380 569; x_wconf 91' lang='Fraktur'>“</span>\n" +
        "     </span>\n" +
        "    </p>\n" +
        "\n" +
        "    <p class='ocr_par' id='par_17_4' lang='Fraktur' title=\"bbox 187 618 1494 744\">\n" +
        "     <span class='ocr_line' id='line_17_8' title=\"bbox 303 618 1494 676; baseline 0.001 -11; x_size 54; x_descenders 11; x_ascenders 12\">\n" +
        "      <span class='ocrx_word' id='word_17_43' title='bbox 303 618 465 675; x_wconf 93'>„Sie?“</span>\n" +
        "      <span class='ocrx_word' id='word_17_44' title='bbox 508 623 632 676; x_wconf 91'>\uD83D\uDD25Fenia\uD83E\uDDEF</span>\n" +
        "      <span class='ocrx_word' id='word_17_45' title='bbox 659 623 799 676; x_wconf 92' lang='frk'>heftete</span>\n" +
        "      <span class='ocrx_word' id='word_17_46' title='bbox 821 621 899 664; x_wconf 95' lang='frk'>voll</span>\n" +
        "      <span class='ocrx_word' id='word_17_47' title='bbox 920 619 1115 672; x_wconf 96' lang='frk'>Intereſſe</span>\n" +
        "      <span class='ocrx_word' id='word_17_48' title='bbox 1138 621 1219 673; x_wconf 96' lang='frk'>ihre</span>\n" +
        "      <span class='ocrx_word' id='word_17_49' title='bbox 1242 621 1494 674; x_wconf 96' lang='frk'>hellbraunen</span>\n" +
        "     </span>\n" +
        "     <span class='ocr_line' id='line_17_9' title=\"bbox 187 688 1488 744; baseline 0.003 -14; x_size 53; x_descenders 12; x_ascenders 11\">\n" +
        "      <span class='ocrx_word' id='word_17_50' title='bbox 187 688 325 742; x_wconf 96' lang='frk'>Augen</span>\n" +
        "      <span class='ocrx_word' id='word_17_51' title='bbox 357 691 428 742; x_wconf 96' lang='frk'>auf</span>\n" +
        "      <span class='ocrx_word' id='word_17_52' title='bbox 458 692 538 743; x_wconf 96' lang='frk'>ihn.</span>\n" +
        "      <span class='ocrx_word' id='word_17_53' title='bbox 597 692 676 734; x_wconf 96' lang='frk'>Sie</span>\n" +
        "      <span class='ocrx_word' id='word_17_54' title='bbox 706 703 791 733; x_wconf 96' lang='frk'>war</span>\n" +
        "      <span class='ocrx_word' id='word_17_55' title='bbox 823 702 918 742; x_wconf 96' lang='frk'>ganz</span>\n" +
        "      <span class='ocrx_word' id='word_17_56' title='bbox 950 690 1029 731; x_wconf 95' lang='frk'>und</span>\n" +
        "      <span class='ocrx_word' id='word_17_57' title='bbox 1062 701 1134 741; x_wconf 96' lang='frk'>gar</span>\n" +
        "      <span class='ocrx_word' id='word_17_58' title='bbox 1165 688 1224 732; x_wconf 96' lang='frk'>bei</span>\n" +
        "      <span class='ocrx_word' id='word_17_59' title='bbox 1257 691 1322 733; x_wconf 96' lang='frk'>der</span>\n" +
        "      <span class='ocrx_word' id='word_17_60' title='bbox 1348 692 1488 744; x_wconf 95' lang='frk'>Sache.</span>\n" +
        "     </span>\n" +
        "    </p>\n" +
        "\n" +
        "    <p class='ocr_par' id='par_17_5' lang='frk' title=\"bbox 303 755 770 811\">\n" +
        "     <span class='ocr_line' id='line_17_10' title=\"bbox 303 755 770 811; baseline -0.002 -11; x_size 55; x_descenders 11; x_ascenders 15\">\n" +
        "      <span class='ocrx_word' id='word_17_61' title='bbox 303 758 499 809; x_wconf 96'>„Warum</span>\n" +
        "      <span class='ocrx_word' id='word_17_62' title='bbox 531 759 631 811; x_wconf 96'>nicht</span>\n" +
        "      <span class='ocrx_word' id='word_17_63' title='bbox 663 755 770 810; x_wconf 91'>ich?“</span>\n" +
        "     </span>\n" +
        "    </p>\n" +
        "\n" +
        "    <p class='ocr_par' id='par_17_6' lang='frk' title=\"bbox 185 823 1498 1084\">\n" +
        "     ";
    Reader peekingReader = new PeekingReader(new SanitizingXmlFilter(new StringReader(fragment)), 2048, 16384);
    HocrParser parser = new HocrParser(peekingReader);
    List<OcrBox> boxes = parser.stream().collect(Collectors.toList());
    assertThat(boxes).isNotEmpty();
    /*
    char[] cbuf = new char[12128];
    int outOff = 0;
    // 128 OK
    int numRead = peekingReader.read(cbuf, 0, 128);
    outOff += numRead;
    // 4000 !! is 3929 in integration test?!
    numRead = peekingReader.read(cbuf, outOff, 4000);
    outOff += numRead;
    // 336 !! is 403 in integration test, but 407 in unit test? what is going on!?
    numRead = peekingReader.read(cbuf, outOff, 4000);
    outOff += numRead;
    // -1  !! is 3 in integreation test?!
    numRead = peekingReader.read(cbuf, outOff, 4000);
     */
  }
}