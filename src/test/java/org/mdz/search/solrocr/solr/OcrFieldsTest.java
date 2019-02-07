package org.mdz.search.solrocr.solr;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
    Path ocrPath = dataPath.resolve("miniocr.xml");
    assertU(adoc(
        "external_ocr_text", new String(Files.readAllBytes(ocrPath), StandardCharsets.UTF_16),
        "id", "31337"));
    assertU(adoc(
        "stored_ocr_text", new String(Files.readAllBytes(ocrPath), StandardCharsets.UTF_16),
        "id", "41337"));
    assertU(commit());
  }

  private static SolrQueryRequest xmlQ(String... args) throws Exception {
    SolrQueryRequest q = req(args);
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
  public void testBooleanQuery2() throws Exception {
    SolrQueryRequest req = xmlQ(
        "q", "((München AND Bayern) OR Hamburg)", "hl", "true", "hl.fields", "ocr_text", "hl.usePhraseHighlighter", "true", "df", "external_ocr_text", "hl.ctxTag", "l", "hl.ctxSize", "2", "hl.snippets", "10");
    assertQ(req,
        "count(//lst[@name='highlighting']/lst[@name='31337']/arr[@name='external_ocr_text']/lst)=10");
  }
}
