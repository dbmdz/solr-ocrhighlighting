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

public class AltoMultiTest extends SolrTestCaseJ4 {
  @BeforeClass
  public static void beforeClass() throws Exception {
    initCore("solrconfig.xml", "schema.xml", "src/test/resources/solr", "general");

    Path ocrBasePath = Paths.get("src/test/resources/data/alto_multi");
    String ptr =
        Files.list(ocrBasePath)
            .filter(p -> p.getFileName().toString().startsWith("1860-11-30_01"))
            .map(p -> p.toAbsolutePath().toString())
            .sorted()
            .collect(Collectors.joining("+"));
    assertU(adoc("ocr_text", ptr, "id", "42"));
    String debugPtr =
        Paths.get("src/test/resources/data/alto_multi/1865-05-24_01-00001.xml").toAbsolutePath()
            + "[35835:36523,36528:37059,37064:86504,86509:138873,138878:193611,193616:244420,244425:247169]+"
            + Paths.get("src/test/resources/data/alto_multi/1865-05-24_01-00002.xml")
                .toAbsolutePath()
            + "[2223:25803,25808:32247,32252:38770,38775:85408,85413:88087,88092:120911,120916:149458,"
            + "149463:178686,178691:220893,220898:231618,231623:242459]";
    assertU(adoc("ocr_text", debugPtr, "id", "84"));
    assertU(commit());
  }

  private static SolrQueryRequest xmlQ(String... extraArgs) {
    Map<String, String> args =
        new HashMap<>(
            ImmutableMap.<String, String>builder()
                .put("hl", "true")
                .put("hl.ocr.fl", "ocr_text")
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
  public void testFirstPageSearch() {
    SolrQueryRequest req = xmlQ("q", "bettembourg");
    assertQ(
        req,
        "count(//arr[@name='snippets']/lst)=3",
        "count(//arr[@name='pages'][1]/lst)=3",
        "(//arr[@name='pages']/lst/str[@name='id'])[2]/text()='P1'",
        "(//arr[@name='pages']/lst/int[@name='width'])[2]/text()='3170'",
        "(//arr[@name='pages']/lst/int[@name='height'])[2]/text()='4890'",
        "(//int[@name='pageIdx'])[1]/text()='0'",
        "(//arr[@name='snippets']/lst/str[@name='text'])[2]/text()='Embranchement de <em>Bettembourg</em> à Esch s/A.'",
        "(//arr[@name='snippets']/lst/str[@name='text'])[3]/text()='Retour à Luxembourg pour les deux embranchements Départ de <em>Bettembourg</em>: 6h. 50 du soir. |'",
        "(//arr[@name='snippets']/lst/str[@name='text'])[1]/text()='Embranchement de <em>Bettembourg</em> à Ottange.'");
  }

  @Test
  public void testLastPageSearch() {
    SolrQueryRequest req = xmlQ("q", "\"moniteur universel\"");
    assertQ(
        req,
        "count(//arr[@name='snippets']/lst)=1",
        "//arr[@name='pages']/lst/str[@name='id']/text()='P4'",
        "//arr[@name='pages']/lst/int[@name='width']/text()='3170'",
        "//arr[@name='pages']/lst/int[@name='height']/text()='4890'",
        "(//int[@name='pageIdx'])[1]/text()='0'",
        "(//arr[@name='snippets']/lst/str[@name='text'])[1]/text()='burcien zu diirfcn. On écrit de Saint-Pétersbourg, en date du 18 novembre, au <em>Moniteur universel</em>:'");
  }

  @Test
  public void testCrossPageHit() {
    SolrQueryRequest req = xmlQ("q", "\"nirgends Bediirfnisi\"~10", "hl.ocr.limitBlock", "none");
    assertQ(
        req,
        "count(//arr[@name='snippets']/lst)=1",
        "count(//arr[@name='pages']/lst)=2",
        "(//arr[@name='pages']/lst/str[@name='id'])[1]/text()='P2'",
        "(//arr[@name='pages']/lst/int[@name='width'])[1]/text()='3170'",
        "(//arr[@name='pages']/lst/int[@name='height'])[1]/text()='4890'",
        "(//arr[@name='pages']/lst/str[@name='id'])[2]/text()='P3'",
        "(//arr[@name='pages']/lst/int[@name='width'])[2]/text()='3170'",
        "(//arr[@name='pages']/lst/int[@name='height'])[2]/text()='4890'",
        "(//arr[@name='regions']/lst/int[@name='pageIdx'])[1]/text()='0'",
        "(//arr[@name='regions']/lst/int[@name='pageIdx'])[2]/text()='1'",
        "//arr[@name='snippets']/lst/str[@name='text']/text()='Vcschllisse motivirt hat. wird gcwisi nirgends mchr gcwurdigt und tiaukbarer anerkannt, alS hier in unscrcm Landc; abcr auch <em>nirgendS H bas Bediirfnisi</em> nach ciucr cndlichen That des Bundcs dringender alS hier. Die bishcrige Veschliïssc dcs Blindes, welche die Erfullung'");
  }

  @Test
  public void testRegionsWithHyphenation() {
    SolrQueryRequest req = xmlQ("q", "Kalifat");
    assertQ(
        req,
        "count(//arr[@name='snippets']/lst)=1",
        "contains(//arr[@name='snippets']/lst/str[@name='text']/text(), '<em>kalifat</em>')",
        "count(//arr[@name='highlights']/arr/lst)=2");
  }
}
