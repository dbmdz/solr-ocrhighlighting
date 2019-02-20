package org.mdz.search.solrocr.solr;

import com.google.common.collect.ImmutableMap;
import java.io.ByteArrayOutputStream;
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
import org.mdz.search.solrocr.formats.alto.AltoByteOffsetsParser;

public class AltoTest extends SolrTestCaseJ4 {
  @BeforeClass
  public static void beforeClass() throws Exception {
    initCore("solrconfig.xml", "schema.xml", "src/test/resources/solr", "alto");

    Path ocrPath = Paths.get("src/test/resources/data/alto.xml");
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    AltoByteOffsetsParser.parse(Files.readAllBytes(ocrPath), bos);
    String text = bos.toString(StandardCharsets.UTF_8.toString());
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
  public void testAlto() throws Exception {
    SolrQueryRequest req = xmlQ("q", "svadag");
    assertQ(req,
        "count(//lst[@name='ocrHighlighting']/lst[@name='42']/lst[@name='ocr_text']/arr/lst)=1",
        "//str[@name='text'][1]/text()='H.ieifics Menighed Kl. eg for Nicolai Mcniohed Kl. > > Slet. <) Sftensan« om-exler««««« boggeMenighederzNicvlaiMe- Mighed h«r »stenfang paa <em>Svadag</em> ferrkkvm»,cnde. ->) Sknf- «ewaal til Ssndagen holdes i H.geNesKirte sorNicolaiMemghch L?verda.en Kl.«, oz f»r H-geist-S Menighed Kk72. e) Bor-'",
        "//lst[@name='region'][1]/int[@name='ulx']/text()=436",
        "//arr[@name='highlights']/arr/lst[1]/int[@name='ulx']/text()=1504"
    );
  }
}