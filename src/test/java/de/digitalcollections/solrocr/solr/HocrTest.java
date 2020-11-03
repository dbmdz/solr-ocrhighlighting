package de.digitalcollections.solrocr.solr;

import com.google.common.collect.ImmutableMap;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.apache.solr.SolrTestCaseJ4;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.request.SolrQueryRequest;
import org.junit.BeforeClass;
import org.junit.Test;

public class HocrTest extends SolrTestCaseJ4 {
  @BeforeClass
  public static void beforeClass() throws Exception {
    //initCore("solrconfig.xml", "schema.xml", "src/test/resources/solr", "hocr");
    initCore("solrconfig.xml", "schema.xml", "src/test/resources/solr", "general");

    assertU(adoc(
        "some_text",
        "Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor "
        + "incididunt ut labore et dolore magna aliqua. Ut enim ad minim veniam, quis nostrud "
        + "exercitation ullamco laboris nisi ut aliquip ex ea commodo consequat. Duis aute "
        + "irure dolor in reprehenderit in voluptate velit esse cillum dolore eu fugiat nulla "
        + "pariatur. Excepteur sint occaecat cupidatat non proident, sunt in culpa qui officia "
        + "deserunt mollit anim id est laborum.", "id", "1337"));
    Path ocrPath = Paths.get("src/test/resources/data/hocr.html");
    assertU(adoc("ocr_text", ocrPath.toString(), "id", "42"));
    assertU(adoc("ocr_text", String.format("%s[3033380:3066395]", ocrPath.toString()), "id", "84"));
    Path multiColPath = Paths.get("src/test/resources/data/multicolumn.hocr");
    assertU(adoc("ocr_text", multiColPath.toString(),  "id", "96"));
    assertU(commit());
  }

  private static SolrQueryRequest xmlQ(String... extraArgs) {
    Map<String, String> args = new HashMap<>(ImmutableMap.<String, String>builder()
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

    SolrQueryRequest q = req(
        args.entrySet().stream().flatMap(e -> Stream.of(e.getKey(), e.getValue())).toArray(String[]::new));
    ModifiableSolrParams params = new ModifiableSolrParams(q.getParams());
    params.set("indent", "true");
    q.setParams(params);
    return q;
  }

  @Test
  public void testHocr() throws Exception {
    SolrQueryRequest req = xmlQ("q", "tamara");
    assertQ(req,
        "count(//lst[@name='ocrHighlighting']/lst[@name='42']/lst[@name='ocr_text']/arr/lst)=2",
        "//str[@name='text'][1]/text()='lung. Ganz vorn lagen die drei mittelmäßigen, aber ſehr populären "
            + "Jlluſtrationen zu Lermontoffs „Dämon“: die Verführung <em>Tamaras</em> durch den Dämon, ihre "
            + "Hingabe an ihn, ihr Tod durch ihn. Fenia wies mit dem Muff darauf hin.'",
        "//arr[@name='regions'][1]/lst/int[@name='ulx']/text()=146",
        "//arr[@name='highlights']/arr/lst[1]/int[@name='ulx']/text()=361"
    );
  }

  @Test
  public void testWeightMatches() throws Exception {
    SolrQueryRequest req = xmlQ("q", "\"Verführung Tamaras\"", "hl.weightMatches", "true");
    assertQ(req,
            "count(//lst[@name='ocrHighlighting']/lst[@name='42']/lst[@name='ocr_text']/arr/lst)=1",
            "//str[@name='text'][1]/text()='lung. Ganz vorn lagen die drei mittelmäßigen, aber ſehr populären "
            + "Jlluſtrationen zu Lermontoffs „Dämon“: die <em>Verführung Tamaras</em> durch den Dämon, ihre "
            + "Hingabe an ihn, ihr Tod durch ihn. Fenia wies mit dem Muff darauf hin.'",
            "//arr[@name='regions'][1]/lst/int[@name='ulx']/text()=146",
            "//arr[@name='highlights']/arr/lst[1]/int[@name='ulx']/text()=83");
  }

  @Test
  public void testSubsectionHighlighting() throws Exception {
    SolrQueryRequest req = xmlQ("q", "\"brütenden Sonnenwärme\"", "hl.weightMatches", "true");
    assertQ(req,
            "count(//lst[@name='ocrHighlighting']/lst)=2",
            "//lst[@name='ocrHighlighting']/lst[@name='42']//arr[@name='snippets']/lst/str[@name='text']/text()='"
            + "glückſeligen Klang ihres gedämpften Lachens und mit dem Eindru> der: <em>brütenden Sonnenwärme</em> um "
            + "uns. Wer will abwägen, wie unendlich zufällig, wie rein äußerlich bedingt es vielleicht iſt, wenn mir "
            + "bei dieſer Erinnerung'");
    req = xmlQ("q", "\"Volfslieder heller von den Lippen\"", "hl.weightMatches", "true");
    assertQ(req,
            "count(//lst[@name='ocrHighlighting']/lst)=1");
  }

    @Test
  public void testPageNumberAtBeginningOfPage() throws Exception {
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
  public void testOverlappingMatches() throws Exception {
    SolrQueryRequest req = xmlQ("q", "\"pirate vessel\"~10", "hl.weightMatches", "true",
                                "hl.ocr.contextSize", "0");
    assertQ(
        req,
        "//lst[@name='ocrHighlighting']//str[@name='text']/text()='<em>pirates hove their vessel that the other pirates</em> had trashed'",
        "//lst[@name='ocrHighlighting']//arr[@name='highlights']//str[@name='text']/text()='pirates hove their vessel that the other pirates'");
  }

  @Test
  public void testAbsoluteHighlightRegions() throws Exception {
    SolrQueryRequest req = xmlQ("q", "Verführung", "hl.ocr.absoluteHighlights", "true");
    assertQ(req,
            "//arr[@name='regions'][1]/lst/int[@name='ulx']/text()=146",
            "//arr[@name='highlights']/arr/lst[1]/int[@name='ulx']/text()=229");
  }

  @Test
  public void testLimitBlockHonored() throws Exception {
    SolrQueryRequest req = xmlQ("q", "Japan", "hl.ocr.absoluteHighlights", "true");
    assertQ(req,
            "//int[@name='numTotal']/text()='6'",
            "(//arr[@name='snippets']/lst/str[@name='text']/text())[1]='object too hastily, in addition to the facts already stated it ought to be remarked, that Kunnpfer describes the coast of<em>Japan</em>'");
  }

  @Test
  public void testAccidentalMerge() throws Exception {
    SolrQueryRequest req = xmlQ("q", "Robinson");
    assertQ(
        req,
        "count(//arr[@name='regions']/lst)=1",
        "count(//arr[@name='highlights']/arr)=2");
  }

  @Test
  public void testMultiPageSnippet() throws Exception {
    SolrQueryRequest req = xmlQ("q", "\"max werner hochzeit\"~10", "hl.ocr.limitBlock", "none", "hl.weightMatches", "true");
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
  public void testMergedRegionExceedsContext() throws Exception {
    SolrQueryRequest req = xmlQ("q", "\"lord's prayer\"", "hl.weightMatches", "true");
    assertQ(req,
            "count(//arr[@name='regions']/lst)=1",
            "//str[@name='text'][1]/text()=\"Witches are reported (amongst many other hellish observations, whereby "
            + "they obh'ge them\u00ADselves to Satan) to say the <em>Lord's prayer</em> back\u00ADwards. "
            + "Are there not many, who, though they do not. pronounce the syllables of the <em>Lord's "
            + "prayer</em> retrograde (their discretion will not suf\u00ADfer them to be betrayed to such a "
            + "nonsense sin), yet they transpose it in effect, desiring their\"");
  }

  @Test
  public void testHyphenationIsResolved() throws Exception {
    SolrQueryRequest req = xmlQ("q", "\"themselves to Satan\"", "hl.weightMatches", "true");
    assertQ(req, "count(//arr[@name='regions']/lst)=1");
  }

  @Test
  public void testMaskedDocumentIsIndexed() throws Exception {
    SolrQueryRequest req = xmlQ("q", "Vögelchen");
    assertQ(
        req,
        "count(//arr[@name='regions']/lst)=2",
        "contains(//lst[@name='84']//arr[@name='snippets']/lst/str[@name='text']/text(), '<em>Vögelchen</em>')");
  }

  @Test
  public void testHighlightingTimeout() throws Exception {
    // This test can only check for the worst case, since checking for partial results is unlikely to be stable across
    // multiple environments due to timing issues.
    SolrQueryRequest req = xmlQ("q", "Vögelchen", "hl.ocr.timeAllowed", "1");
    assertQ(
        req,
        "//bool[@name='partialOcrHighlights']='true'",
        "count(//lst[@name='ocrHighlighting']/lst)=2",
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
    String firstSnip = "auf und ſchob <em>Fenia</em> ſo eilig er konnte hinein. Denn vom untern Sto>werk wurden "
        + "Stimmen laut, und einer der Tatarenkellner geleitete fremde Herrſchaften hinauf.";
    SolrQueryRequest req = xmlQ("q", "fenia", "hl.snippets", "1");
    assertQ(req, String.format("//arr[@name='snippets']/lst[1]//str[@name='text']/text()='%s'", firstSnip));
    req = xmlQ("q", "fenia", "hl.snippets", "100");
    assertQ(req, String.format("//arr[@name='snippets']/lst[1]//str[@name='text']/text()='%s'", firstSnip));
  }

  @Test
  public void testAlignSpans() {
    String unalignedText = "in die erſte Jugend, die nicht wiederkam. Lou <em>Andreas</em>-Salomet, Fenitſchka. 12";
    String alignedText = "in die erſte Jugend, die nicht wiederkam. Lou <em>Andreas-Salomet,</em> Fenitſchka. 12";
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
    SolrQueryRequest req = req(
        "q", "\"occaecat cupidatat\"", "hl.fl", "some_text", "df", "some_text", "hl", "true");
    assertQ(req, "count(//lst[@name='highlighting']//arr[@name='some_text'])=1");
    assertQ(req, "count(//lst[@name='ocrHighlighting']//arr[@name='snippets'])=0");
  }

  @Test
  public void testCombinedHighlighting() {
    SolrQueryRequest req = xmlQ(
        "q", "\"occaecat cupidatat\" Salomet", "hl.fl", "some_text", "defType", "edismax",
        "qf", "some_text ocr_text");
    assertQ(req, "count(//lst[@name='highlighting']//arr[@name='some_text'])=1");
    assertQ(req, "count(//lst[@name='ocrHighlighting']//arr[@name='snippets'])=1");
  }

  @Test
  public void testAlternatives() {
    SolrQueryRequest req = xmlQ("q", "ocr_text:\"nathanael brush\"");
    assertQ(req, "count(//arr[@name='snippets'])='1'");
    req = xmlQ("q", "ocr_text:\"natlianiel brush\"");
    assertQ(req, "count(//arr[@name='snippets'])='1'");
  }
}
