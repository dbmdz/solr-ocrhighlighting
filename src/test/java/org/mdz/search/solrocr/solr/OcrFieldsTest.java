package org.mdz.search.solrocr.solr;

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

public class OcrFieldsTest extends SolrTestCaseJ4 {
  @BeforeClass
  public static void beforeClass() throws Exception {
    initCore("solrconfig.xml", "schema.xml", "src/test/resources/solr", "miniocr");

    Path dataPath = Paths.get("src", "test", "resources", "data").toAbsolutePath();

    assertU(adoc(
        "some_text",
        "Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor "
            + "incididunt ut labore et dolore magna aliqua. Ut enim ad minim veniam, quis nostrud "
            + "exercitation ullamco laboris nisi ut aliquip ex ea commodo consequat. Duis aute "
            + "irure dolor in reprehenderit in voluptate velit esse cillum dolore eu fugiat nulla "
            + "pariatur. Excepteur sint occaecat cupidatat non proident, sunt in culpa qui officia "
            + "deserunt mollit anim id est laborum.", "id", "1337"));
    Path ocrPath = dataPath.resolve("31337_ocr.xml");
    assertU(adoc(
        "external_ocr_text", new String(Files.readAllBytes(ocrPath), StandardCharsets.UTF_16),
        "id", "31337"));
    assertU(adoc(
        "stored_ocr_text", new String(Files.readAllBytes(ocrPath), StandardCharsets.UTF_16),
        "id", "41337"));
    assertU(commit());
  }

  protected static SolrQueryRequest xmlQ(String... extraArgs) throws Exception {
    Map<String, String> args = new HashMap<>(ImmutableMap.<String, String>builder()
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
  public void testSingleTerm() throws Exception {
    SolrQueryRequest req = xmlQ("q", "München");
    assertQ(req,
        "count(//lst[@name='ocrHighlighting']/lst[@name='31337']/lst[@name='external_ocr_text']/arr/lst)=3",
            "//str[@name='text'][1]/text()='Bayerische Staatsbibliothek <em>München</em>'",
            "//lst[@name='region'][1]/float[@name='ulx']/text()='0.4949'",
            "//lst[@name='region'][1]/float[@name='uly']/text()='0.0071'",
            "//lst[@name='region'][1]/float[@name='lrx']/text()='0.571'",
            "//lst[@name='region'][1]/float[@name='lry']/text()='0.028499998'",
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
          "count(//str[@name='page' and text() = '26'])=1");

  }
}
