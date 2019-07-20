package de.digitalcollections.solrocr.solr;

import com.google.common.collect.ImmutableMap;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.solr.SolrTestCaseJ4;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.request.SolrQueryRequest;
import org.junit.BeforeClass;
import org.junit.Test;

public class AltoMultiEscapedTest extends SolrTestCaseJ4 {
  @BeforeClass
  public static void beforeClass() throws Exception {
    initCore("solrconfig.xml", "schema.xml", "src/test/resources/solr", "alto");

    Path ocrBasePath = Paths.get("src/test/resources/data/alto_multi");
    String ptr = Files.list(ocrBasePath)
        .filter(p -> p.getFileName().toString().endsWith(".xml"))
        .map(p -> p.toAbsolutePath().toString())
        .sorted()
        .collect(Collectors.joining("+"));
    assertU(adoc("ocr_text", ptr, "id", "42"));
    assertU(commit());
  }

  private static SolrQueryRequest xmlQ(String... extraArgs) {
    Map<String, String> args = new HashMap<>(
        ImmutableMap.<String, String>builder()
           .put("hl", "true")
           .put("hl.fl", "ocr_text")
           .put("hl.weightMatches", "true")
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
  public void testFirstPageSearch() {
    SolrQueryRequest req = xmlQ("q", "bettembourg");
    assertQ(
        req,
        "count(//arr[@name='snippets']/lst)=3",
        "(//str[@name='page'])[1]/text()='P1'",
        "(//arr[@name='snippets']/lst/str[@name='text'])[1]/text()='Embranchement de <em>Bettembourg</em> à Esch s/A.'",
        "(//arr[@name='snippets']/lst/str[@name='text'])[2]/text()='Retour à Luxembourg pour les deux embranchements Départ de <em>Bettembourg</em>: 6h. 50 du soir. |'",
        "(//arr[@name='snippets']/lst/str[@name='text'])[3]/text()='Embranchement de <em>Bettembourg</em> à Ottange.'");
  }

  @Test
  public void testLastPageSearch() {
    SolrQueryRequest req = xmlQ("q", "\"moniteur universel\"");
    assertQ(
        req,
        "count(//arr[@name='snippets']/lst)=1",
        "(//str[@name='page'])[1]/text()='P4'",
        "(//arr[@name='snippets']/lst/str[@name='text'])[1]/text()='burcien zu diirfcn. On écrit de Saint-Pétersbourg, en date du 18 novembre, au <em>Moniteur universel</em>:'");
  }

  @Test
  public void testCrossPageHit() {
    SolrQueryRequest req = xmlQ("q", "\"nirgends Bediirfnisi\"~10", "hl.ocr.limitBlock", "none");
    assertQ(
        req,
        "count(//arr[@name='snippets']/lst)=1",
        "(//arr[@name='regions']/lst/str[@name='page'])[1]/text()='P2'",
        "(//arr[@name='regions']/lst/str[@name='page'])[2]/text()='P3'",
        "//arr[@name='snippets']/lst/str[@name='text']/text()='Vcschllisse motivirt hat. wird gcwisi nirgends mchr gcwurdigt und tiaukbarer anerkannt, alS hier in unscrcm Landc; abcr auch <em>nirgendS H bas Bediirfnisi</em> nach ciucr cndlichen That des Bundcs dringender alS hier. Die bishcrige Veschliïssc dcs Blindes, welche die Erfullung'");
  }
}
