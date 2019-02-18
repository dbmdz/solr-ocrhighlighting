package org.mdz.search.solrocr.solr;

import com.google.common.collect.ImmutableMap;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.apache.solr.BaseDistributedSearchTestCase;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.handler.component.HighlightComponent;
import org.apache.solr.request.SolrQueryRequest;
import org.junit.BeforeClass;
import org.junit.Test;

public class DistributedTest extends BaseDistributedSearchTestCase {
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
    assertU(BaseDistributedSearchTestCase.commit());
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
    for (int i = 0; i < extraArgs.length; i += 2) {
      String key = extraArgs[i];
      String val = extraArgs[i + 1];
      args.put(key, val);
    }

    SolrQueryRequest q = req(args.entrySet().stream().flatMap(e -> Stream
        .of(e.getKey(), e.getValue())).toArray(String[]::new));
    ModifiableSolrParams params = new ModifiableSolrParams(q.getParams());
    params.set("indent", "true");
    q.setParams(params);
    return q;
  }

  @Test
  @ShardsRepeat(max=5)
  public void testPhraseQuery() throws Exception {
    SolrQueryRequest req = xmlQ("q", "\"Bayerische Staatsbibliothek\"");
    assertQ(req,
            "count(//lst[@name='highlighting']/lst[@name='31337']/lst[@name='external_ocr_text']/arr/lst/"
          + "str[@name='text' and contains(text(), '<em>Bayerische</em> <em>Staatsbibliothek</em>')])=1");
  }
}
