package de.digitalcollections.solrocr.solr;

import com.google.common.collect.ImmutableMap;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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

public class MiniOcrEscapedTest extends SolrTestCaseJ4 {
  @BeforeClass
  public static void beforeClass() throws Exception {
    initCore("solrconfig.xml", "schema.xml", "src/test/resources/solr", "miniocr_escaped");

    Path dataPath = Paths.get("src", "test", "resources", "data").toAbsolutePath();

    assertU(adoc(
        "some_text",
        "Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor "
            + "incididunt ut labore et dolore magna aliqua. Ut enim ad minim veniam, quis nostrud "
            + "exercitation ullamco laboris nisi ut aliquip ex ea commodo consequat. Duis aute "
            + "irure dolor in reprehenderit in voluptate velit esse cillum dolore eu fugiat nulla "
            + "pariatur. Excepteur sint occaecat cupidatat non proident, sunt in culpa qui officia "
            + "deserunt mollit anim id est laborum.", "id", "1337"));
    Path ocrPath = dataPath.resolve("miniocr_escaped.xml");
    assertU(adoc(
        "external_ocr_text", new String(Files.readAllBytes(ocrPath), StandardCharsets.US_ASCII),
        "id", "31337"));
    assertU(adoc(
        "stored_ocr_text", new String(Files.readAllBytes(ocrPath), StandardCharsets.US_ASCII),
        "id", "41337"));
    assertU(commit());
  }

  protected static SolrQueryRequest xmlQ(String... extraArgs) throws Exception {
    Map<String, String> args = new HashMap<>(ImmutableMap.<String, String>builder()
        .put("defType", "edismax")
        .put("hl", "true")
        .put("hl.fields", "external_ocr_text")
        .put("hl.usePhraseHighlighter", "true")
        .put("df", "external_ocr_text")
        .put("hl.ctxTag", "l")
        .put("hl.ctxSize", "2")
        .put("hl.snippets", "10")
        .put("fl", "id")
        .build());
    for (int i=0; i < extraArgs.length; i+=2) {
      String key = extraArgs[i];
      String val = extraArgs[i+1];
      args.put(key, val);
    }

    SolrQueryRequest q = req(args.entrySet().stream().flatMap(e -> Stream.of(e.getKey(), e.getValue())).toArray(String[]::new));
    ModifiableSolrParams params = new ModifiableSolrParams(q.getParams());
    params.set("indent", "true");
    q.setParams(params);
    return q;
  }

  @Test
  public void testMixedHighlighting() throws Exception {
    SolrQueryRequest req = xmlQ("q", "commodo münchen",
                                "qf", "some_text external_ocr_text", "df", "some_text",
                                "hl.fields", "some_text,ocr_text", "hl.weightMatches", "true");
    assertQ(req,
            "count(//lst[@name='highlighting']/lst[@name='1337']/arr[@name='some_text']/str)=1",
            ".//arr[@name='some_text']/str/text()=' aliquip ex ea <em>commodo</em> consequat. Duis aute irure dolor in reprehenderit in voluptate velit esse cillum'",
            "count(//lst[@name='ocrHighlighting']/lst[@name='31337']/lst[@name='external_ocr_text']/arr/lst)=3");
  }

  @Test
  public void testSingleTerm() throws Exception {
    SolrQueryRequest req = xmlQ("q", "München");
    assertQ(req,
        "count(//lst[@name='ocrHighlighting']/lst[@name='31337']/lst[@name='external_ocr_text']/arr/lst)=3",
            "//str[@name='text'][1]/text()='Bayerische Staatsbibliothek <em>München</em>'",
            "//arr[@name='regions'][1]/lst/float[@name='ulx']/text()='0.4949'",
            "//arr[@name='regions'][1]/lst/float[@name='uly']/text()='0.0071'",
            "//arr[@name='regions'][1]/lst/float[@name='lrx']/text()='0.571'",
            "//arr[@name='regions'][1]/lst/float[@name='lry']/text()='0.028499998'",
            "count(//arr[@name='highlights'])=3",
            "//arr[@name='highlights'][1]/arr/lst/float[@name='ulx']/text()='0.2339'",
            "//arr[@name='highlights'][1]/arr/lst/float[@name='uly']/text()='0.7149'",
            "//arr[@name='highlights'][1]/arr/lst/float[@name='lrx']/text()='0.7805'",
            "//arr[@name='highlights'][1]/arr/lst/int[@name='lry']/text()='1'");
  }

  @Test
  public void testStoredHighlighting() throws Exception {
    SolrQueryRequest req = xmlQ("q", "München", "hl.fields", "stored_ocr_text", "df", "stored_ocr_text");
    assertQ(req,
            "count(//lst[@name='ocrHighlighting']/lst[@name='41337']/lst[@name='stored_ocr_text']/arr/lst)=3");
  }

  @Test
  public void testBooleanQuery() throws Exception {
    SolrQueryRequest req = xmlQ("q", "((München AND Italien) OR Landsherr)");
    assertQ(req,
        "count(//lst[@name='ocrHighlighting']/lst[@name='31337']/lst[@name='external_ocr_text']/arr/lst)=10");
  }

  @Test
  public void testBooleanQueryNoMatch() throws Exception {
    SolrQueryRequest req = xmlQ("q", "((München AND Rotterdam) OR Mexico)");
    assertQ(req,
        "count(//lst[@name='ocrHighlighting']/lst[@name='31337']/lst[@name='external_ocr_text']/arr/lst)=4");
  }

  @Test
  public void testWildcardQuery() throws Exception {
    SolrQueryRequest req = xmlQ("q", "(Mün* OR Magdebur?)");
    assertQ(req,
        "count(//lst[@name='ocrHighlighting']/lst[@name='31337']/lst[@name='external_ocr_text']/arr/lst)=10");
  }

  @Test
  public void testWildcardQueryAtTheBeginning() throws Exception {
    SolrQueryRequest req = xmlQ("q", "*deburg");
    assertQ(req,
        "count(//lst[@name='ocrHighlighting']/lst[@name='31337']/lst[@name='external_ocr_text']/arr/lst/str[@name='text' and contains(text(),'agdeburg')])=10");
  }

  @Test
  public void testWildcardQueryIntheMiddle() throws Exception {
    SolrQueryRequest req = xmlQ("q", "Mü*hen");
    assertQ(req,
        "count(//lst[@name='ocrHighlighting']/lst[@name='31337']/lst[@name='external_ocr_text']/arr/lst/str[@name='text' and contains(text(),'Münche')])=3");
  }

  @Test
  public void testWildcardQueryAtTheEnd() throws Exception {
    SolrQueryRequest req = xmlQ("q", "Münch*");
    assertQ(req,
        "count(//lst[@name='ocrHighlighting']/lst[@name='31337']/lst[@name='external_ocr_text']/arr/lst/str[@name='text' and contains(text(),'Münche')])=3");
  }

  @Test
  public void testWildcardQueryWithWildcardOnly() throws Exception {
    SolrQueryRequest req = xmlQ("q", "*");
    assertQ(req,
        "count(//lst[@name='ocrHighlighting']/lst[@name='31337']/lst[@name='external_ocr_text']/arr/lst)=0");
  }

  @Test
  public void testWildcardQueryWithAsteriskAndNoResults() throws Exception {
    SolrQueryRequest req = xmlQ("q", "Zzz*");
    assertQ(req,
        "count(//lst[@name='ocrHighlighting']/lst[@name='31337']/lst[@name='external_ocr_text']/arr/lst)=0");
  }

  @Test
  public void testWildcardQueryWithNoResults() throws Exception {
    SolrQueryRequest req = xmlQ("q", "Z?z");
    assertQ(req,
        "count(//lst[@name='ocrHighlighting']/lst[@name='31337']/lst[@name='external_ocr_text']/arr/lst)=0");
  }

  @Test
  public void testWildcardQueryWithWildcardForUmlautInTheMiddle() throws Exception {
    SolrQueryRequest req = xmlQ("q", "M?nchen");
    assertQ(req,
        "count(//lst[@name='ocrHighlighting']/lst[@name='31337']/lst[@name='external_ocr_text']/arr/lst/str[@name='text' and contains(text(),'Münche')])>0");
    assertQ(req,
        "count(//lst[@name='ocrHighlighting']/lst[@name='31337']/lst[@name='external_ocr_text']/arr/lst/str[@name='text' and contains(text(),'manche')])>0");
  }

  @Test
  public void testPhraseQuery() throws Exception {
    SolrQueryRequest req = xmlQ("q", "\"Bayerische Staatsbibliothek\"");
    assertQ(req,
        "count(//lst[@name='ocrHighlighting']/lst[@name='31337']/lst[@name='external_ocr_text']/arr/lst/str[@name='text' and contains(text(), '<em>Bayerische</em> <em>Staatsbibliothek</em>')])=1");
  }

  @Test
  public void testPhraseQueryWithNoResults() throws Exception {
    SolrQueryRequest req = xmlQ("q", "\"Münchener Stadtbibliothek\"");
    assertQ(req,
        "count(//lst[@name='ocrHighlighting']/lst[@name='31337']/lst[@name='external_ocr_text']/arr/lst)=0");
  }

  @Test
  public void testFuzzyQueryWithSingleTerm() throws Exception {
    SolrQueryRequest req = xmlQ("q", "bayrisch~");
    assertQ(req,
        "count(//lst[@name='ocrHighlighting']/lst[@name='31337']/lst[@name='external_ocr_text']/arr/lst/str[@name='text' and contains(text(),'Bayerisch')])=1");
  }

  @Test
  public void testFuzzyQueryWithSingleTermAndGreaterProximity() throws Exception {
    SolrQueryRequest req = xmlQ("q", "baurisch~3");
    assertQ(req,
        "count(//lst[@name='ocrHighlighting']/lst[@name='31337']/lst[@name='external_ocr_text']/arr/lst/str[@name='text' and contains(text(),'Bayerisch')])=1");
  }

  @Test
  public void testCombinedFuzzyQuery() throws Exception {
    SolrQueryRequest req = xmlQ("q", "Magdepurg~ OR baurisch~3", "hl.snippets", "100");
    assertQ(req,
        "count(//lst[@name='ocrHighlighting']/lst[@name='31337']/lst[@name='external_ocr_text']/arr/lst/str[@name='text' and contains(text(),'Bayerisch')])=1");
    assertQ(req,
        "count(//lst[@name='ocrHighlighting']/lst[@name='31337']/lst[@name='external_ocr_text']/arr/lst/str[@name='text' and contains(text(),'Magdebur')])>1");
  }

  @Test
  public void testFuzzyQueryWithNoResults() throws Exception {
    SolrQueryRequest req = xmlQ("q", "Makdepurk~ OR baurysk~2");
    assertQ(req,
        "count(//lst[@name='ocrHighlighting']/lst[@name='31337']/lst[@name='external_ocr_text']/arr/lst)=0");
  }

  @Test
  public void testProximityQueryWithOneHighlighting() throws Exception {
    SolrQueryRequest req = xmlQ("q", "\"Bayerische München\"~3");
    assertQ(req,
        "count(//lst[@name='ocrHighlighting']/lst[@name='31337']/lst[@name='external_ocr_text']/arr/lst/str[@name='text' and contains(text(),'<em>Bayerische</em> Staatsbibliothek <em>München</em>')])=1");
  }

  @Test
  public void testProximityQueryWithTwoHighlightings() throws Exception {
    SolrQueryRequest req = xmlQ("q", "\"Bayerische Ausgabe\"~10");
    assertQ(req,
        "count(//lst[@name='ocrHighlighting']/lst[@name='31337']/lst[@name='external_ocr_text']/arr/lst)=2");
  }

  @Test
  public void testWeightMatches() throws Exception {
    SolrQueryRequest req = xmlQ("q", "\"Bayerische Staatsbibliothek München\"", "hl.weightMatches", "true");
    assertQ(req,
            "count(//lst[@name='ocrHighlighting']/lst[@name='31337']/lst[@name='external_ocr_text']/arr/lst)=1",
            "//str[@name='text']/text()='<em>Bayerische Staatsbibliothek München</em>'",
            "//arr[@name='highlights']/arr/lst[1]/float[@name='ulx']/text()='0.1695'",
            "//arr[@name='highlights']/arr/lst[1]/int[@name='uly']/text()='0'");
  }

  @Test
  public void testFilterByPage() throws Exception {
    SolrQueryRequest req = xmlQ("q", "München", "hl.ocr.pageId", "26", "fq", "id:31337");
    assertQ(req,
        "count(//str[@name='page' and text() != '26'])=0",
          "count(//str[@name='page' and text() = '26'])=2");
  }

  @Test
  public void testCornerHit() throws Exception {
    SolrQueryRequest req = xmlQ("q", "Anerkennungsfrage");
    assertQ(req, "//arr[@name='highlights']/arr/lst/int[@name='lry']='1'");
  }

  @Test
  public void testMultiPageSnippet() throws Exception {
    SolrQueryRequest req = xmlQ("q", "\"london nachrichten\"~5", "hl.ocr.limitBlock", "none", "hl.weightMatches", "true");
    assertQ(
        req,
        "//str[@name='text'][1]/text()='5proc. Metall. 69, 00. 1854er Looſe –. Bankactien 797, 00. Nordbahn –. National-Anlehen 74, 10. Credit-Actien 177, 80. St. Eiſenb.-Actien-Cert. 182, 10. Galizier 197, 00. <em>London 108, 90. ien-Nachrichten</em>. i er in ſº. . . 1 eyhfü „Fºº Är º Heinr P? P. ſ º. - Empfehlen º Meyen ( - Leutloff mit -meraüren beenre-e-mehr-die-ergeben Ä-mein Gehrer u. Studirende!'",
        "(//arr[@name='highlights']/arr/lst/str[@name='page'])[1]='9'",
        "(//arr[@name='highlights']/arr/lst/str[@name='page'])[2]='10'",
        "(//arr[@name='regions']/lst/str[@name='page'])[1]='9'",
        "(//arr[@name='regions']/lst/str[@name='page'])[2]='10'");
  }
}
