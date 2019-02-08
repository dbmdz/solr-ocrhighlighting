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
import org.apache.solr.handler.component.HighlightComponent;
import org.apache.solr.request.SolrQueryRequest;
import org.junit.BeforeClass;
import org.junit.Test;

public class OcrFieldsTest extends SolrTestCaseJ4 {
  @BeforeClass
  public static void beforeClass() throws Exception {
    initCore("solrconfig.xml", "schema.xml", "src/test/resources/solr", "miniocr");

    HighlightComponent hlComp = (HighlightComponent) h.getCore().getSearchComponent("highlight");
    assertTrue("wrong highlighter: " + hlComp.getHighlighter().getClass(),
        hlComp.getHighlighter() instanceof SolrOcrHighlighter);

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

  private static SolrQueryRequest xmlQ(String... extraArgs) throws Exception {
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
    SolrQueryRequest req = xmlQ(
        "q", "München", "hl", "true", "hl.fields", "external_ocr_text", "hl.usePhraseHighlighter", "true", "df",
        "external_ocr_text", "hl.ctxTag", "l", "hl.ctxSize", "2", "hl.snippets", "10");
    assertQ(req,
        "count(//lst[@name='highlighting']/lst[@name='31337']/arr[@name='external_ocr_text']/lst)=3");
  }

  @Test
  public void testStoredHighlighting() throws Exception {
    SolrQueryRequest req = xmlQ(
        "q", "München", "hl", "true", "hl.fields", "stored_ocr_text", "hl.usePhraseHighlighter", "true", "df",
        "stored_ocr_text", "hl.ctxTag", "l", "hl.ctxSize", "2", "hl.snippets", "10", "fl", "id");
    assertQ(req,
            "count(//lst[@name='highlighting']/lst[@name='41337']/arr[@name='stored_ocr_text']/lst)=3");
  }

  @Test
  public void testBooleanQuery() throws Exception {
    SolrQueryRequest req = xmlQ(
        "q", "((München AND Italien) OR Landsherr)", "hl", "true", "hl.fields", "ocr_text", "hl.usePhraseHighlighter", "true", "df", "external_ocr_text", "hl.ctxTag", "l", "hl.ctxSize", "2", "hl.snippets", "10");
    assertQ(req,
        "count(//lst[@name='highlighting']/lst[@name='31337']/arr[@name='external_ocr_text']/lst)=10");
  }

  @Test
  public void testBooleanQueryNoMatch() throws Exception {
    SolrQueryRequest req = xmlQ(
        "q", "((München AND Rotterdam) OR Mexico)", "hl", "true", "hl.fields", "ocr_text", "hl.usePhraseHighlighter", "true", "df", "external_ocr_text", "hl.ctxTag", "l", "hl.ctxSize", "2", "hl.snippets", "10");
    assertQ(req,
        "count(//lst[@name='highlighting']/lst[@name='31337']/arr[@name='ocr_text']/lst)=0");
  }

  @Test
  public void testWildcardQuery() throws Exception {
    SolrQueryRequest req = xmlQ(
        "q", "(Mün* OR Magdebur?)", "hl", "true", "hl.fields", "ocr_text", "hl.usePhraseHighlighter", "true", "df", "external_ocr_text", "hl.ctxTag", "l", "hl.ctxSize", "2", "hl.snippets", "10");
    assertQ(req,
        "count(//lst[@name='highlighting']/lst[@name='31337']/arr[@name='external_ocr_text']/lst)=10");
  }

  @Test
  public void testWildcardQueryAtTheBeginning() throws Exception {
    SolrQueryRequest req = xmlQ(
        "q", "*deburg", "hl", "true", "hl.fields", "ocr_text", "hl.usePhraseHighlighter", "true", "df", "external_ocr_text", "hl.ctxTag", "l", "hl.ctxSize", "2", "hl.snippets", "10");
    assertQ(req,
        "count(//lst[@name='highlighting']/lst[@name='31337']/arr[@name='external_ocr_text']/lst/str[@name='text' and contains(text(),'Magdebur')])=10");
  }

  @Test
  public void testWildcardQueryIntheMiddle() throws Exception {
    SolrQueryRequest req = xmlQ(
        "q", "Mü*hen", "hl", "true", "hl.fields", "ocr_text", "hl.usePhraseHighlighter", "true", "df", "external_ocr_text", "hl.ctxTag", "l", "hl.ctxSize", "2", "hl.snippets", "10");
    assertQ(req,
        "count(//lst[@name='highlighting']/lst[@name='31337']/arr[@name='external_ocr_text']/lst/str[@name='text' and contains(text(),'Münche')])=3");
  }

  @Test
  public void testWildcardQueryAtTheEnd() throws Exception {
    SolrQueryRequest req = xmlQ(
        "q", "Münch*", "hl", "true", "hl.fields", "ocr_text", "hl.usePhraseHighlighter", "true", "df", "external_ocr_text", "hl.ctxTag", "l", "hl.ctxSize", "2", "hl.snippets", "10");
    assertQ(req,
        "count(//lst[@name='highlighting']/lst[@name='31337']/arr[@name='external_ocr_text']/lst/str[@name='text' and contains(text(),'Münche')])=3");
  }

  @Test
  public void testWildcardQueryWithWildcardOnly() throws Exception {
    SolrQueryRequest req = xmlQ(
        "q", "*", "hl", "true", "hl.fields", "ocr_text", "hl.usePhraseHighlighter", "true", "df", "external_ocr_text", "hl.ctxTag", "l", "hl.ctxSize", "2", "hl.snippets", "10", "hl.highlightMultiTerm", "true");
    assertQ(req,
        "count(//lst[@name='highlighting']/lst[@name='31337']/arr[@name='external_ocr_text']/lst)=10");
  }

  @Test
  public void testWildcardQueryWithAsteriskAndNoResults() throws Exception {
    SolrQueryRequest req = xmlQ(
        "q", "Zzz*", "hl", "true", "hl.fields", "ocr_text", "hl.usePhraseHighlighter", "true", "df", "external_ocr_text", "hl.ctxTag", "l", "hl.ctxSize", "2", "hl.snippets", "10");
    assertQ(req,
        "count(//lst[@name='highlighting']/lst[@name='31337']/arr[@name='external_ocr_text']/lst)=0");
  }

  @Test
  public void testWildcardQueryWithNoResults() throws Exception {
    SolrQueryRequest req = xmlQ(
        "q", "Z?z", "hl", "true", "hl.fields", "ocr_text", "hl.usePhraseHighlighter", "true", "df", "external_ocr_text", "hl.ctxTag", "l", "hl.ctxSize", "2", "hl.snippets", "10");
    assertQ(req,
        "count(//lst[@name='highlighting']/lst[@name='31337']/arr[@name='external_ocr_text']/lst)=0");
  }

  @Test
  public void testWildcardQueryWithWildcardForUmlautInTheMiddle() throws Exception {
    SolrQueryRequest req = xmlQ(
        "q", "M?nchen", "hl", "true", "hl.fields", "ocr_text", "hl.usePhraseHighlighter", "true", "df", "external_ocr_text", "hl.ctxTag", "l", "hl.ctxSize", "2", "hl.snippets", "10");
    assertQ(req,
        "count(//lst[@name='highlighting']/lst[@name='31337']/arr[@name='external_ocr_text']/lst/str[@name='text' and contains(text(),'Münche')])>0");
    assertQ(req,
        "count(//lst[@name='highlighting']/lst[@name='31337']/arr[@name='external_ocr_text']/lst/str[@name='text' and contains(text(),'manche')])>0");
  }

  @Test
  public void testPhraseQuery() throws Exception {
    SolrQueryRequest req = xmlQ(
        "q", "\"Bayerische Staatsbibliothek\"", "hl", "true", "hl.fields", "ocr_text", "hl.usePhraseHighlighter", "true", "df", "external_ocr_text", "hl.ctxTag", "l", "hl.ctxSize", "2", "hl.snippets", "10");
    assertQ(req,
        "count(//lst[@name='highlighting']/lst[@name='31337']/arr[@name='external_ocr_text']/lst)=1");
  }

  @Test
  public void testPhraseQueryWithNoResults() throws Exception {
    SolrQueryRequest req = xmlQ(
        "q", "\"Münchener Stadtbibliothek\"", "hl", "true", "hl.fields", "ocr_text", "hl.usePhraseHighlighter", "true", "df", "external_ocr_text", "hl.ctxTag", "l", "hl.ctxSize", "2", "hl.snippets", "10");
    assertQ(req,
        "count(//lst[@name='highlighting']/lst[@name='31337']/arr[@name='external_ocr_text']/lst)=0");
  }

  @Test
  public void testMultiPhraseQuery() throws Exception {
    SolrQueryRequest req = xmlQ(
        "q", "\"Bayerische Staatsbib*\"", "hl", "true", "hl.fields", "ocr_text", "hl.usePhraseHighlighter", "true", "df", "external_ocr_text", "hl.ctxTag", "l", "hl.ctxSize", "2", "hl.snippets", "10");
    assertQ(req,
        "count(//lst[@name='highlighting']/lst[@name='31337']/arr[@name='external_ocr_text']/lst)=1");
  }

  @Test
  public void testFuzzyQueryWithSingleTerm() throws Exception {
    SolrQueryRequest req = xmlQ(
        "q", "bayrisch~", "hl", "true", "hl.fields", "ocr_text", "hl.usePhraseHighlighter", "true", "df", "external_ocr_text", "hl.ctxTag", "l", "hl.ctxSize", "2", "hl.snippets", "10");
    assertQ(req,
        "count(//lst[@name='highlighting']/lst[@name='31337']/arr[@name='external_ocr_text']/lst/str[@name='text' and contains(text(),'Bayerisch')])=1");
  }

  @Test
  public void testFuzzyQueryWithSingleTermAndGreaterProximity() throws Exception {
    SolrQueryRequest req = xmlQ(
        "q", "baurisch~3", "hl", "true", "hl.fields", "ocr_text", "hl.usePhraseHighlighter", "true", "df", "external_ocr_text", "hl.ctxTag", "l", "hl.ctxSize", "2", "hl.snippets", "10");
    assertQ(req,
        "count(//lst[@name='highlighting']/lst[@name='31337']/arr[@name='external_ocr_text']/lst/str[@name='text' and contains(text(),'Bayerisch')])=1");
  }

  @Test
  public void testCombinedFuzzyQuery() throws Exception {
    SolrQueryRequest req = xmlQ(
        "q", "Magdepurg~ OR baurisch~3", "hl", "true", "hl.fields", "ocr_text", "hl.usePhraseHighlighter", "true", "df", "external_ocr_text", "hl.ctxTag", "l", "hl.ctxSize", "2", "hl.snippets", "10");
    assertQ(req,
        "count(//lst[@name='highlighting']/lst[@name='31337']/arr[@name='external_ocr_text']/lst/str[@name='text' and contains(text(),'Bayerisch')])=1");
    assertQ(req,
        "count(//lst[@name='highlighting']/lst[@name='31337']/arr[@name='external_ocr_text']/lst/str[@name='text' and contains(text(),'Magedbur')])>1");
  }

  @Test
  public void testFuzzyQueryWithNoResults() throws Exception {
    SolrQueryRequest req = xmlQ(
        "q", "Makdepurg~ OR baurysk~2", "hl", "true", "hl.fields", "ocr_text", "hl.usePhraseHighlighter", "true", "df", "external_ocr_text", "hl.ctxTag", "l", "hl.ctxSize", "2", "hl.snippets", "10");
    assertQ(req,
        "count(//lst[@name='highlighting']/lst[@name='31337']/arr[@name='external_ocr_text']/lst)=0");
  }

  @Test
  public void testProximityQueryWithOneHighlighting() throws Exception {
    SolrQueryRequest req = xmlQ(
        "q", "\"Bayerische München\"~3", "hl", "true", "hl.fields", "ocr_text", "hl.usePhraseHighlighter", "true", "df", "external_ocr_text", "hl.ctxTag", "l", "hl.ctxSize", "2", "hl.snippets", "10");
    assertQ(req,
        "count(//lst[@name='highlighting']/lst[@name='31337']/arr[@name='external_ocr_text']/lst/str[@name='text' and contains(text(),'<em>Bayerisch</em>e Staatsbibliothek <em>Münche</em>n')])=1");
  }

  @Test
  public void testProximityQueryWithTwoHighlightings() throws Exception {
    SolrQueryRequest req = xmlQ(
        "q", "\"Bayerische Ausgabe\"~10", "hl", "true", "hl.fields", "ocr_text", "hl.usePhraseHighlighter", "true", "df", "external_ocr_text", "hl.ctxTag", "l", "hl.ctxSize", "2", "hl.snippets", "10");
    assertQ(req,
        "count(//lst[@name='highlighting']/lst[@name='31337']/arr[@name='external_ocr_text']/lst)=2");
  }
}
