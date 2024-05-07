package com.github.dbmdz.solrocr.solr;

import com.carrotsearch.randomizedtesting.annotations.ThreadLeakFilters;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.lucene.tests.util.QuickPatchThreadsFilter;
import org.apache.solr.SolrIgnoredThreadsFilter;
import org.apache.solr.SolrTestCaseJ4;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.request.SolrQueryRequest;
import org.junit.BeforeClass;
import org.junit.Test;

@ThreadLeakFilters(
    defaultFilters = true,
    filters = {
      SolrIgnoredThreadsFilter.class,
      QuickPatchThreadsFilter.class,
      HlThreadsFilter.class
    })
public class HocrTest extends SolrTestCaseJ4 {
  @BeforeClass
  public static void beforeClass() throws Exception {
    // Needed since https://github.com/apache/solr/commit/16657ccab092
    System.setProperty("solr.install.dir", "./");
    initCore("solrconfig.xml", "schema.xml", "src/test/resources/solr", "general");

    assertU(
        adoc(
            "some_text",
            "Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor "
                + "incididunt ut labore et dolore magna aliqua. Ut enim ad minim veniam, quis nostrud "
                + "exercitation ullamco laboris nisi ut aliquip ex ea commodo consequat. Duis aute "
                + "irure dolor in reprehenderit in voluptate velit esse cillum dolore eu fugiat nulla "
                + "pariatur. Excepteur sint occaecat cupidatat non proident, sunt in culpa qui officia "
                + "deserunt mollit anim id est laborum.",
            "id",
            "1337"));
    Path ocrPath = Paths.get("src/test/resources/data/hocr.html");
    assertU(adoc("ocr_text", ocrPath.toString(), "id", "42"));
    assertU(
        adoc(
            "ocr_text",
            String.format(Locale.US, "%s[3001845:3065626]", ocrPath.toString()),
            "id",
            "84"));
    Path multiColPath = Paths.get("src/test/resources/data/multicolumn.hocr");
    assertU(adoc("ocr_text", multiColPath.toString(), "id", "96"));
    String ptr =
        Files.walk(Paths.get("src/test/resources/data/chronicling_hocr"), 1)
            .sorted()
            .filter(Files::isRegularFile)
            .map(Path::toString)
            .collect(Collectors.joining("+"));
    assertU(adoc("ocr_text", ptr, "id", "758"));
    Path path = Paths.get("src/test/resources/data/space_after.html");
    assertU(adoc("ocr_text", path.toString(), "id", "396"));
    assertU(commit());
  }

  private static SolrQueryRequest xmlQ(String... extraArgs) {
    Map<String, String> args =
        new HashMap<>(
            ImmutableMap.<String, String>builder()
                .put("hl", "true")
                .put("hl.ocr.fl", "ocr_text")
                .put("hl.usePhraseHighlighter", "true")
                .put("df", "ocr_text")
                .put("hl.ctxTag", "ocr_line")
                .put("hl.ctxSize", "2")
                .put("hl.snippets", "10")
                .put("fl", "id")
                .build());
    for (int i = 0; i < extraArgs.length; i += 2) {
      String key = extraArgs[i];
      String val = extraArgs[i + 1];
      args.put(key, val);
    }

    SolrQueryRequest q =
        req(
            args.entrySet().stream()
                .flatMap(e -> Stream.of(e.getKey(), e.getValue()))
                .toArray(String[]::new));
    ModifiableSolrParams params = new ModifiableSolrParams(q.getParams());
    params.set("indent", "true");
    q.setParams(params);
    return q;
  }

  @Test
  public void testHocr() {
    SolrQueryRequest req = xmlQ("q", "tamara");
    assertQ(
        req,
        "count(//lst[@name='ocrHighlighting']/lst[@name='42']/lst[@name='ocr_text']/arr/lst)=2",
        "//str[@name='text'][1]/text()='lung. Ganz vorn lagen die drei mittelmäßigen, aber ſehr populären "
            + "Jlluſtrationen zu Lermontoffs „Dämon“: die Verführung <em>Tamaras</em> durch den Dämon, ihre "
            + "Hingabe an ihn, ihr Tod durch ihn. Fenia wies mit dem Muff darauf hin.'",
        "//arr[@name='regions'][1]/lst/int[@name='ulx']/text()=146",
        "//arr[@name='highlights']/arr/lst[1]/int[@name='ulx']/text()=361");
  }

  @Test
  public void testWeightMatches() {
    SolrQueryRequest req = xmlQ("q", "\"Verführung Tamaras\"", "hl.weightMatches", "true");
    assertQ(
        req,
        "count(//lst[@name='ocrHighlighting']/lst[@name='42']/lst[@name='ocr_text']/arr/lst)=1",
        "//str[@name='text'][1]/text()='lung. Ganz vorn lagen die drei mittelmäßigen, aber ſehr populären "
            + "Jlluſtrationen zu Lermontoffs „Dämon“: die <em>Verführung Tamaras</em> durch den Dämon, ihre "
            + "Hingabe an ihn, ihr Tod durch ihn. Fenia wies mit dem Muff darauf hin.'",
        "//arr[@name='regions'][1]/lst/int[@name='ulx']/text()=146",
        "//arr[@name='highlights']/arr/lst[1]/int[@name='ulx']/text()=83");
  }

  @Test
  public void testSubsectionHighlighting() {
    SolrQueryRequest req = xmlQ("q", "\"brütenden Sonnenwärme\"", "hl.weightMatches", "true");
    assertQ(
        req,
        "count(//lst[@name='ocrHighlighting']/lst)=2",
        "//lst[@name='ocrHighlighting']/lst[@name='42']//arr[@name='snippets']/lst/str[@name='text']/text()='"
            + "glückſeligen Klang ihres gedämpften Lachens und mit dem Eindru> der: <em>brütenden Sonnenwärme</em> um "
            + "uns. Wer will abwägen, wie unendlich zufällig, wie rein äußerlich bedingt es vielleicht iſt, wenn mir "
            + "bei dieſer Erinnerung'");
    req = xmlQ("q", "\"Volfslieder heller von den Lippen\"", "hl.weightMatches", "true");
    assertQ(req, "count(//lst[@name='ocrHighlighting']/lst)=2");
  }

  @Test
  public void testPageNumberAtBeginningOfPage() {
    SolrQueryRequest req = xmlQ("q", "\"peramentvollere Glänzendere\"", "hl.weightMatches", "true");
    assertQ(
        req,
        "count(//arr[@name='pages']/lst)=1",
        "//arr[@name='pages']/lst/str[@name='id']/text()='page_109'",
        "//arr[@name='pages']/lst/int[@name='width']/text()='1600'",
        "//arr[@name='pages']/lst/int[@name='height']/text()='2389'",
        "//lst[@name='ocrHighlighting']//int[@name='pageIdx']/text()='0'");
  }

  @Test
  public void testOverlappingMatches() {
    SolrQueryRequest req =
        xmlQ("q", "\"pirate vessel\"~10", "hl.weightMatches", "true", "hl.ocr.contextSize", "0");
    assertQ(
        req,
        "//lst[@name='ocrHighlighting']//str[@name='text']/text()='<em>pirates hove their vessel that the other pirates</em> had trashed'",
        "//lst[@name='ocrHighlighting']//arr[@name='highlights']//str[@name='text']/text()='pirates hove their vessel that the other pirates'");
  }

  @Test
  public void testAbsoluteHighlightRegions() {
    SolrQueryRequest req = xmlQ("q", "Verführung", "hl.ocr.absoluteHighlights", "true");
    assertQ(
        req,
        "//arr[@name='regions'][1]/lst/int[@name='ulx']/text()=146",
        "//arr[@name='highlights']/arr/lst[1]/int[@name='ulx']/text()=229");
  }

  @Test
  public void testLimitBlockHonored() {
    SolrQueryRequest req = xmlQ("q", "Japan", "hl.ocr.absoluteHighlights", "true", "fq", "id:42");
    assertQ(
        req,
        "//int[@name='numTotal']/text()='6'",
        "(//arr[@name='snippets']/lst/str[@name='text']/text())[1]='object too hastily, in addition to the facts already stated it ought to be remarked, that Kunnpfer describes the coast of <em>Japan</em>'");
  }

  @Test
  public void testAccidentalMerge() {
    SolrQueryRequest req = xmlQ("q", "Robinson");
    assertQ(req, "count(//arr[@name='regions']/lst)=1", "count(//arr[@name='highlights']/arr)=2");
  }

  @Test
  public void testMultiPageSnippet() {
    SolrQueryRequest req =
        xmlQ(
            "q",
            "\"max werner hochzeit\"~10",
            "hl.ocr.limitBlock",
            "none",
            "hl.weightMatches",
            "true");
    assertQ(
        req,
        "//str[@name='text'][1]/text()='einer Verwandten ihres zukünftigen Mannes, die im Auslande ſtudiert und kürzlich promoviert habe. Tief im Winter, Mitte Januar, reiſte <em>Max Werner zur Hochzeit</em> ſeiner Schweſter in die ruſſiſche Provinz. Dort, auf dem Gut von deren Freunden, wo eine Un- menge fremder Gäſte untergebracht waren, ſah er mitten'",
        "count(//arr[@name='pages']/lst)=2",
        "(//arr[@name='pages']/lst/str[@name='id'])[1]/text()='page_31'",
        "(//arr[@name='pages']/lst/int[@name='width'])[1]/text()='1600'",
        "(//arr[@name='pages']/lst/int[@name='height'])[1]/text()='2389'",
        "(//arr[@name='pages']/lst/str[@name='id'])[2]/text()='page_32'",
        "(//arr[@name='regions']/lst/int[@name='pageIdx'])[1]='0'",
        "(//arr[@name='regions']/lst/int[@name='pageIdx'])[2]='1'",
        "(//arr[@name='highlights']/arr/lst/int[@name='parentRegionIdx'])[1]='0'",
        "(//arr[@name='highlights']/arr/lst/int[@name='parentRegionIdx'])[2]='1'",
        "count(//arr[@name='regions']/lst)=2");
  }

  @Test
  public void testMergedRegionExceedsContext() {
    SolrQueryRequest req = xmlQ("q", "\"lord's prayer\"", "hl.weightMatches", "true");
    assertQ(
        req,
        "count(//arr[@name='regions']/lst)=1",
        "//str[@name='text'][1]/text()=\"Witches are reported (amongst many other hellish observations, whereby "
            + "they obh'ge themselves to Satan) to say the <em>Lord's prayer</em> backwards. "
            + "Are there not many, who, though they do not. pronounce the syllables of the <em>Lord's "
            + "prayer</em> retrograde (their discretion will not suffer them to be betrayed to such a "
            + "nonsense sin), yet they transpose it in effect, desiring their\"");
  }

  @Test
  public void testHyphenationIsResolved() {
    SolrQueryRequest req = xmlQ("q", "\"themselves to Satan\"", "hl.weightMatches", "true");
    assertQ(req, "count(//arr[@name='regions']/lst)=1");
  }

  @Test
  public void testMaskedDocumentIsIndexed() {
    SolrQueryRequest req = xmlQ("q", "Vögelchen");
    assertQ(
        req,
        "count(//arr[@name='regions']/lst)=2",
        "contains(//lst[@name='84']//arr[@name='snippets']/lst/str[@name='text']/text(), '<em>Vögelchen</em>')");
  }

  @Test
  public void testHighlightingTimeout() {
    // This test can only check for the worst case, since checking for partial results is unlikely
    // to be stable across multiple environments due to timing issues.
    SolrQueryRequest req = xmlQ("q", "Vögelchen", "hl.ocr.timeAllowed", "0");
    assertQ(
        req,
        "//bool[@name='partialOcrHighlights']='true'",
        "count(//lst[@name='ocrHighlighting']/lst)=0",
        "count(//arr[@name='snippets'])=0");
  }

  @Test
  public void testMultiColumnSnippet() {
    SolrQueryRequest req = xmlQ("q", "\"kaffe rechnungs\"", "hl.weightMatches", "true");
    assertQ(
        req,
        "count(//lst[@name='ocrHighlighting']//arr[@name='snippets'])=1",
        "count(//arr[@name='snippets']/lst)=1",
        "count(//arr[@name='snippets']/lst/arr[@name='regions']/lst)=2",
        "count(//arr[@name='snippets']/lst/arr[@name='highlights']/arr)=1",
        "//arr[@name='regions']/lst[1]/str[@name='text']='Die General-Verwaltung der königlichen Eiſenbahnen "
            + "beſteht aus einem Vorſtande, zwei Räthen, wovon einer der Komptabilität kundig ſeyn muß, einem "
            + "Ober-Ingenieur, einem Maſchinenmeiſter, den erforderlichen <em>Kaffe-,</em>'",
        "//arr[@name='regions']/lst[2]/str[@name='text']='<em>Rechnungs</em>-, Kanzlei-, Regiſtratur- und techniſchen "
            + "Gehülfen-Perſonal Der Geſchäftsgang iſt bei der k. Eiſenbahn, ſoferne nicht für beſondere Fälle "
            + "kollegiale Behandlung vorgeſchrieben iſt, bureaukratiſch, und der Vorſtand'",
        "(//int[@name='parentRegionIdx'])[1]=0",
        "(//int[@name='parentRegionIdx'])[2]=1");
  }

  @Test
  public void testPassageContextMerge() {
    SolrQueryRequest req = xmlQ("q", "haribo");
    assertQ(req, "count(//arr[@name='snippets']/lst)=1");
  }

  @Test
  public void testPassageSorting() {
    String firstSnip =
        "auf und ſchob <em>Fenia</em> ſo eilig er konnte hinein. Denn vom untern Sto>werk wurden "
            + "Stimmen laut, und einer der Tatarenkellner geleitete fremde Herrſchaften hinauf.";
    SolrQueryRequest req = xmlQ("q", "fenia", "hl.snippets", "1");
    assertQ(
        req,
        String.format(
            Locale.US, "//arr[@name='snippets']/lst[1]//str[@name='text']/text()='%s'", firstSnip));
    req = xmlQ("q", "fenia", "hl.snippets", "100");
    assertQ(
        req,
        String.format(
            Locale.US, "//arr[@name='snippets']/lst[1]//str[@name='text']/text()='%s'", firstSnip));
  }

  @Test
  public void testAlignSpans() {
    String unalignedText =
        "in die erſte Jugend, die nicht wiederkam. Lou <em>Andreas</em>-Salomet, Fenitſchka. 12";
    String alignedText =
        "in die erſte Jugend, die nicht wiederkam. Lou <em>Andreas-Salomet,</em> Fenitſchka. 12";
    SolrQueryRequest req = xmlQ("q", "Andreas", "hl.ocr.pageId", "page_181");
    assertQ(
        req,
        "//arr[@name='snippets']/lst/str[@name='text']/text()='" + unalignedText + "'",
        "//arr[@name='regions']/lst/str[@name='text']/text()='" + unalignedText + "'");
    req = xmlQ("q", "Andreas", "hl.ocr.pageId", "page_181", "hl.ocr.alignSpans", "true");
    assertQ(
        req,
        "//arr[@name='snippets']/lst/str[@name='text']/text()='" + alignedText + "'",
        "//arr[@name='regions']/lst/str[@name='text']/text()='" + alignedText + "'");
  }

  @Test
  public void testRegularHighlighting() {
    SolrQueryRequest req =
        req("q", "\"occaecat cupidatat\"", "hl.fl", "some_text", "df", "some_text", "hl", "true");
    assertQ(req, "count(//lst[@name='highlighting']//arr[@name='some_text'])=1");
    assertQ(req, "count(//lst[@name='ocrHighlighting']//arr[@name='snippets'])=0");
  }

  @Test
  public void testImplicitRegularHighlighting() {
    SolrQueryRequest req =
        req(
            "q",
            "\"occaecat cupidatat\"",
            "defType",
            "edismax",
            "qf",
            "some_text ocr_text",
            "hl",
            "true");
    assertQ(req, "count(//lst[@name='highlighting']//arr[@name='some_text'])=1");
    assertQ(req, "count(//lst[@name='ocrHighlighting']//arr[@name='snippets'])=0");
  }

  @Test
  public void testCombinedHighlighting() {
    SolrQueryRequest req =
        xmlQ(
            "q",
            "\"occaecat cupidatat\" Salomet",
            "defType",
            "edismax",
            "qf",
            "some_text ocr_text");
    assertQ(req, "count(//lst[@name='highlighting']//arr[@name='some_text'])=1");
    assertQ(req, "count(//lst[@name='ocrHighlighting']//arr[@name='snippets']/lst)=1");
  }

  @Test
  public void testAlternatives() {
    SolrQueryRequest req = xmlQ("q", "ocr_text:\"nathanael brush\"");
    assertQ(req, "count(//arr[@name='snippets']/lst)='1'");
    req = xmlQ("q", "ocr_text:\"natlianiel brush\"");
    assertQ(req, "count(//arr[@name='snippets']/lst)='1'");
  }

  public void testChronicling() {
    SolrQueryRequest req = xmlQ("q", "ocr_text:\"arkansas state\"", "hl.weightMatches", "true");
    assertQ(req, "count(//arr[@name='snippets']/lst)='8'");
  }

  @Test
  public void testSpaceIssue() {
    SolrQueryRequest req = xmlQ("q", "ocr_text:\"the dry season and\"");
    assertQ(
        req,
        "count(//arr[@name='snippets'])='1'",
        "contains(//arr[@name='snippets']/lst/str[@name='text'], 'whole of Altar valley')");
  }

  @Test
  public void testMatchOnHyphenation() {
    Path ocrPath = Paths.get("src/test/resources/data/hyphen_match.html");
    assertU(adoc("ocr_text", ocrPath.toString(), "id", "47371"));
    assertU(commit());
    SolrQueryRequest req = xmlQ("q", "ocr_text:\"all former efforts\"", "hl.weightMatches", "true");
    assertQ(
        req,
        "//arr[@name='snippets']/lst/str[@name='text']/text()='than the one given under the Gentry Trade Mark. The street parade this year is said to surpass <em>all former efforts</em> and to be larger and better than previous years. ftvrftuA,'");
  }

  @Test
  public void testHyphenPhraseMatch() {
    Path ocrPath = Paths.get("src/test/resources/data/hyphen_phrasematch.html");
    assertU(adoc("ocr_text", ocrPath.toString(), "id", "47371"));
    assertU(commit());
    SolrQueryRequest req =
        xmlQ("q", "ocr_text:\"whose death was announced\"", "hl.weightMatches", "true");
    assertQ(
        req,
        "contains(//arr[@name='snippets']/lst/str[@name='text']/text(), '<em>whose death was announced</em>')");
  }

  @Test
  public void testIndexCrash() {
    Path ocrPath = Paths.get("src/test/resources/data/indexerror.html");
    assertU(adoc("ocr_text", ocrPath.toString(), "id", "47371"));
    assertU(commit());
  }

  public void testMisplacedClosing() {
    Path ocrPath = Paths.get("src/test/resources/data/misplaced_closing.html");
    assertU(adoc("ocr_text", ocrPath.toString(), "id", "47371"));
    assertU(commit());
    SolrQueryRequest req = xmlQ("q", "ocr_text:\"as to details\"", "hl.weightMatches", "true");
    assertQ(
        req,
        "contains(((//lst[@name='47371']//arr[@name='snippets'])[1]/lst/str[@name='text'])[2]/text(), '<em>details</em>')");
  }

  public void testMissingClosing() {
    Path ocrPath = Paths.get("src/test/resources/data/missing_closing.html");
    assertU(adoc("ocr_text", ocrPath.toString(), "id", "47371"));
    assertU(commit());
    SolrQueryRequest req =
        xmlQ("q", "ocr_text:\"body of the republican\"", "hl.weightMatches", "true");
    assertQ(
        req,
        "contains(((//lst[@name='47371']//arr[@name='snippets'])[1]/lst/str[@name='text'])[1]/text(), '<em>body of independent Republicans</em>')");
  }

  public void testAlternativeHighlighting() {
    Path ocrPath = Paths.get("src/test/resources/data/missing_whitespace.html");
    assertU(adoc("ocr_text", ocrPath.toString(), "id", "47371"));
    assertU(commit());
    SolrQueryRequest req = xmlQ("q", "ocr_text:\"atc of ai flitiia\"", "hl.weightMatches", "true");
    assertQ(
        req,
        "contains(((//lst[@name='47371']//arr[@name='snippets'])[1]/lst/str[@name='text'])[1]/text(), '<em>atC Of ai flItIIa</em>')");
    req = xmlQ("q", "ocr_text:\"atc of ai halrPt\"", "hl.weightMatches", "true");
    assertQ(
        req,
        "contains(((//lst[@name='47371']//arr[@name='snippets'])[1]/lst/str[@name='text'])[1]/text(), '<em>atC Of ai halrPt</em>')");
  }

  public void testMissingWhitespaceDehyphenated() {
    Path ocrPath = Paths.get("src/test/resources/data/missing_whitespace_dehyphenated.html");
    assertU(adoc("ocr_text", ocrPath.toString(), "id", "47371"));
    assertU(commit());
    SolrQueryRequest req = xmlQ("q", "ocr_text:\"rain bide me jrajz\"", "hl.weightMatches", "true");
    assertQ(
        req,
        "contains(((//lst[@name='47371']//arr[@name='snippets'])[1]/lst/str[@name='text'])[1]/text(), '')");
  }

  public void testPartialHyphen() {
    Path ocrPath = Paths.get("src/test/resources/data/hyphen_partial.html");
    assertU(adoc("ocr_text", ocrPath.toString(), "id", "47371"));
    assertU(commit());
    SolrQueryRequest req =
        xmlQ("q", "ocr_text:\"irregular manner in\"", "hl.weightMatches", "true");
    assertQ(
        req,
        "contains(((//lst[@name='47371']//arr[@name='snippets'])[1]/lst/str[@name='text'])[1]/text(), 'Ah for her re-')");
  }

  public void testExtraEndHyphen() {
    Path ocrPath = Paths.get("src/test/resources/data/sn83032300_1887_07_16_3.html");
    assertU(adoc("ocr_text", ocrPath.toString(), "id", "47371"));
    assertU(commit());
    SolrQueryRequest req = xmlQ("q", "ocr_text:\"you never ill and\"", "hl.weightMatches", "true");
    String snipText =
        "Hie recalcitrant organ, of palua beneath Iho right nheuMer blaOe, el dytpepllc aympteRM, "
            + "conciliation and headache? Of course <em>you never ill</em> J, and of courts the lntlTUlual vrm net using "
            + "llcxtetter't Stomach UltUra, or he vionlilnei se hare looked e hare com-cem-";
    assertQ(
        req,
        "//lst[@name='47371']//arr[@name='snippets']/lst/str[@name='text']/text()=\""
            + snipText
            + "\"");
  }

  public void testLongTokenTruncated() {
    Path ocrPath = Paths.get("src/test/resources/data/sn90050306_1921_02_10-8.html");
    assertU(adoc("ocr_text", ocrPath.toString(), "id", "47371"));
    assertU(commit());
    SolrQueryRequest req = xmlQ("q", "ocr_text:\"greatest of all\"", "hl.weightMatches", "true");
    assertQ(
        req,
        "contains(((//lst[@name='47371']//arr[@name='snippets'])[1]/lst/str[@name='text'])[1]/text(), \"i!i!iiiitiiiiiiiiiiiiiiiiiiiiiiniiiiiiiFFf/^|SALEiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiii^'Hi\")");
  }

  public void testHighlightStartInTokenWithEscapes() {
    Path ocrPath = Paths.get("src/test/resources/data/sn83032300_1885_01_177_2.html");
    assertU(adoc("ocr_text", ocrPath.toString(), "id", "47371"));
    assertU(commit());
    SolrQueryRequest req = xmlQ("q", "ocr_text:\"ere what it is\"", "hl.weightMatches", "true");
    assertQ(
        req,
        "contains(((//lst[@name='47371']//arr[@name='snippets'])[1]/lst/str[@name='text'])[1]/text(), \"1'<em>er what</em>\")");
  }

  public void testHighlightEndInTokenWithEscapes() {
    Path ocrPath = Paths.get("src/test/resources/data/sn90050316_1922_12_13_8.html");
    assertU(adoc("ocr_text", ocrPath.toString(), "id", "47371"));
    assertU(commit());
    SolrQueryRequest req = xmlQ("q", "ocr_text:\"returns the ee\"", "hl.weightMatches", "true");
    assertQ(
        req,
        "contains(((//lst[@name='47371']//arr[@name='snippets'])[1]/lst/str[@name='text'])[1]/text(), \"<em>returning Saturday. !>ee</em>\")");
  }

  public void testUnscoredSnippets() {
    SolrQueryRequest req = xmlQ("q", "fenitſchka", "hl.ocr.scorePassages", "off");
    assertQ(
        req,
        "((//lst[@name='42']//arr[@name='pages'])[1]/lst/str[@name='id'])[1]/text()='page_88'",
        "((//lst[@name='42']//arr[@name='pages'])[2]/lst/str[@name='id'])[1]/text()='page_89'",
        "((//lst[@name='42']//arr[@name='pages'])[3]/lst/str[@name='id'])[1]/text()='page_92'",
        "((//lst[@name='42']//arr[@name='pages'])[4]/lst/str[@name='id'])[1]/text()='page_92'",
        "((//lst[@name='42']//arr[@name='pages'])[5]/lst/str[@name='id'])[1]/text()='page_97'");
  }

  public void testEmptyDoc() {
    assertU(adoc("id", "57371"));
    assertU(adoc("id", "57371", "ocr_text", ""));
    assertU(commit());
  }

  public void testIssue288() throws IOException {
    Path ocrPath = Paths.get("src/test/resources/data/issue_288.hocr");
    assertU(
        adoc(
            "ocr_text_stored",
            new String(Files.readAllBytes(ocrPath), StandardCharsets.UTF_8),
            "id",
            "87371"));
    assertU(commit());
    SolrQueryRequest req =
        xmlQ(
            "q",
            "il",
            "hl.snippets",
            "4096",
            "hl.weightMatches",
            "true",
            "df",
            "ocr_text_stored",
            "hl.ocr.fl",
            "ocr_text_stored");
    assertQ(req, "count(.//lst[@name=\"87371\"]//arr[@name='snippets']/lst)=19");
  }

  public void testBrokenEntities() throws IOException {
    Path ocrPath = Paths.get("src/test/resources/data/hocr_broken_entities.html");
    assertU(
        adoc(
            "ocr_text_stored",
            new String(Files.readAllBytes(ocrPath), StandardCharsets.UTF_8),
            "id",
            "87372"));
    assertU(commit());
    SolrQueryRequest req =
        xmlQ(
            "q",
            "Gerichtsvollzieher",
            "hl.snippets",
            "4096",
            "hl.weightMatches",
            "true",
            "hl.ocr.contextSize",
            "4",
            "df",
            "ocr_text_stored",
            "hl.ocr.fl",
            "ocr_text_stored");
    assertQ(
        req,
        "contains(.//lst[@name='87372']//arr[@name='snippets']/lst/str[@name='text']/text(), 'dt_HiFi-i?cBßflpedx1ttonI-iii;_;ikW')");
  }

  public void testBrokenPIs() throws IOException {
    Path ocrPath = Paths.get("src/test/resources/data/hocr_broken_pis.html");
    assertU(
        adoc(
            "ocr_text_stored",
            new String(Files.readAllBytes(ocrPath), StandardCharsets.UTF_8),
            "id",
            "87373"));
    assertU(commit());
    SolrQueryRequest req =
        xmlQ(
            "q",
            "\"junger mensch\"",
            "hl.snippets",
            "4096",
            "hl.weightMatches",
            "true",
            "hl.ocr.contextSize",
            "4",
            "df",
            "ocr_text_stored",
            "hl.ocr.fl",
            "ocr_text_stored");
    assertQ(
        req,
        "contains(.//lst[@name='87373']//arr[@name='snippets']/lst/str[@name='text']/text(), '_?«»«?_i_5t»_?».')");
  }

  public void testBrokenComment() throws IOException {
    Path ocrPath = Paths.get("src/test/resources/data/hocr_broken_comment.html");
    assertU(
        adoc(
            "ocr_text_stored",
            new String(Files.readAllBytes(ocrPath), StandardCharsets.UTF_8),
            "id",
            "87374"));
    assertU(commit());
    SolrQueryRequest req =
        xmlQ(
            "q",
            "eiiigekaufs",
            "hl.snippets",
            "4096",
            "hl.weightMatches",
            "true",
            "hl.ocr.contextSize",
            "4",
            "df",
            "ocr_text_stored",
            "hl.ocr.fl",
            "ocr_text_stored");
    assertQ(
        req,
        "contains(.//lst[@name='87374']//arr[@name='snippets']/lst/str[@name='text']/text(), \"!'_!--,,,_\")");
  }

  public void testHlQParam() {
    SolrQueryRequest req = xmlQ("q", "ocr_text:\"nathanael brush\"", "hl.q", "nathanael");
    assertQ(
        req,
        "count(//arr[@name='snippets']/lst)='1'",
        "contains(//arr[@name='snippets']/lst/str[@name='text'], '<em>Nathanael</em> Brush')");
  }

  public void testHlQParserParam() {
    SolrQueryRequest req =
        xmlQ(
            "defType",
            "lucene",
            "q",
            "ocr_text:\"nathanael brush\"",
            "df",
            "ocr_text_stored", // should break hl.q if hl.qparser is not working properly
            "hl.ocr.qparser",
            "edismax",
            "hl.ocr.q",
            "\"nathanael brush\"",
            "qf",
            "ocr_text", // needed for hl.ocr.qparser to match the hl.ocr.q query
            "hl.weightMatches",
            "true");
    assertQ(
        req,
        "count(//arr[@name='snippets']/lst)='1'",
        "contains(//arr[@name='snippets']/lst/str[@name='text'], '<em>Nathanael Brush</em>')");
  }

  public void testMissingFileDoesNotFailWholeQuery() throws IOException {
    // Create a copy of of a document, with the OCR residing in a temporary directory
    Path tmpDir = createTempDir();
    Files.copy(Paths.get("src/test/resources/data/hocr.html"), tmpDir.resolve("hocr.html"));
    assertU(
        adoc("ocr_text", tmpDir.resolve("hocr.html").toAbsolutePath().toString(), "id", "999999"));
    assertU(commit());

    // With indexing complete, we delete the referenced hOCR in order to cause an error during
    // highlighting
    Files.delete(tmpDir.resolve("hocr.html"));
    Files.delete(tmpDir);

    try {
      SolrQueryRequest req = xmlQ("q", "ocr_text:Nedereien");
      assertQ(
          req,
          "count(//lst[@name='ocrHighlighting']/lst)=1",
          "count(//lst[@name='ocrHighlighting']/lst[@name='999999'])=0");
    } finally {
      assertU(delI("999999"));
      assertU(commit());
    }
  }
}
