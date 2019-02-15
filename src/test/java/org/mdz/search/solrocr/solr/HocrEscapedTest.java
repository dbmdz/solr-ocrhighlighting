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

public class HocrEscapedTest extends SolrTestCaseJ4 {
  @BeforeClass
  public static void beforeClass() throws Exception {
    initCore("solrconfig.xml", "schema.xml", "src/test/resources/solr", "hocr_escaped");

    HighlightComponent hlComp = (HighlightComponent) h.getCore().getSearchComponent("highlight");
    assertTrue("wrong highlighter: " + hlComp.getHighlighter().getClass(),
               hlComp.getHighlighter() instanceof SolrOcrHighlighter);

    Path ocrPath = Paths.get("src/test/resources/data/hocr_escaped.html");
    String text = new String(Files.readAllBytes(ocrPath), StandardCharsets.US_ASCII);
    assertU(adoc("ocr_text", text, "id", "42"));
    assertU(commit());
  }

  private static SolrQueryRequest xmlQ(String... extraArgs) throws Exception {
    Map<String, String> args = new HashMap<>(ImmutableMap.<String, String>builder()
        .put("hl", "true")
        .put("hl.fields", "ocr_text")
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
  public void testEscapedHocr() throws Exception {
    SolrQueryRequest req = xmlQ("q", "tamara");
    assertQ(req,
        "count(//lst[@name='highlighting']/lst[@name='42']/arr[@name='ocr_text']/lst)=2",
        "//str[@name='text'][1]/text()='lung. Ganz vorn lagen die drei mittelmäßigen, aber ſehr populären "
            + "Jlluſtrationen zu Lermontoffs „Dämon“: die Verführung <em>Tamaras</em> durch den Dämon, ihre "
            + "Hingabe an ihn, ihr Tod durch ihn. Fenia wies mit dem Muff darauf hin.'",
        "//lst[@name='region'][1]/int[@name='x']/text()=146",
        "//arr[@name='highlights']/lst[1]/int[@name='x']/text()=361"
    );
  }

  @Test
  public void testWeightMatchesWarning() throws Exception {
    SolrQueryRequest req = xmlQ("q", "\"Verführung Tamaras\"", "hl.weightMatches", "true");
    assertQ(req,
        "count(//lst[@name='highlighting']/lst[@name='42']/arr[@name='ocr_text']/lst)=1",
        "//str[@name='text'][1]/text()='lung. Ganz vorn lagen die drei mittelmäßigen, aber ſehr populären "
            + "Jlluſtrationen zu Lermontoffs „Dämon“: die <em>Verführung Tamaras</em> durch den Dämon, ihre "
            + "Hingabe an ihn, ihr Tod durch ihn. Fenia wies mit dem Muff darauf hin.'",
        "//lst[@name='region'][1]/int[@name='x']/text()=146",
        "//arr[@name='highlights']/lst[1]/int[@name='x']/text()=361");
  }
}