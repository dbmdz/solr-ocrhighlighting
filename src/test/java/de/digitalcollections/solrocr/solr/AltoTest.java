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

public class AltoTest extends SolrTestCaseJ4 {
  @BeforeClass
  public static void beforeClass() throws Exception {
    initCore("solrconfig.xml", "schema.xml", "src/test/resources/solr", "general");

    Path ocrPath = Paths.get("src/test/resources/data/alto.xml");
    assertU(adoc("ocr_text", ocrPath.toString(), "id", "42"));
    ocrPath = Paths.get("src/test/resources/data/bnl_lunion_1865-04-15.xml");
    assertU(adoc("ocr_text", ocrPath.toString(), "id", "43"));
    assertU(commit());
  }

  private static SolrQueryRequest xmlQ(String... extraArgs) throws Exception {
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
  public void testAlto() throws Exception {
    SolrQueryRequest req = xmlQ("q", "svadag");
    assertQ(req,
        "count(//lst[@name='ocrHighlighting']/lst[@name='42']/lst[@name='ocr_text']/arr/lst)=1",
        "//str[@name='text'][1]/text()='H.ieifics Menighed Kl. eg for Nicolai Mcniohed Kl. > > Slet. <) Sftensan« om-exler««««« boggeMenighederzNicvlaiMe- Mighed h«r »stenfang paa <em>Svadag</em> ferrkkvm»,cnde. ->) Sknf- «ewaal til Ssndagen holdes i H.geNesKirte sorNicolaiMemghch L?verda.en Kl.«, oz f»r H-geist-S Menighed Kk72. e) Bor-'",
        "//arr[@name='regions'][1]/lst/int[@name='ulx']/text()=436",
        "//arr[@name='highlights']/arr/lst[1]/int[@name='ulx']/text()=1504"
    );
  }

  @Test
  public void testAccidentalMerge() throws Exception {
    SolrQueryRequest req = xmlQ("q", "ligesom");
    assertQ(req, "count(//arr[@name='highlights']/arr)=2");
  }

  @Test
  public void testEntityRemoval() throws Exception {
    SolrQueryRequest req = xmlQ("q", "committee");
    assertQ(req, "//str[@name='text'][1]/text()='Permanent <em>Committee</em>'");
  }

  @Test
  public void testMultiPageSnippet() throws Exception {
    SolrQueryRequest req = xmlQ("q", "\"jursensen permanent\"", "hl.ocr.limitBlock", "none", "hl.weightMatches", "true");
    assertQ(
        req,
        "//str[@name='text'][1]/text()='kaldes, agter om 3 a 4 Dage at atseile berfea til Stokbolm, Hvorhen han medtager Fragtgods og Passagerer. naar Vedkom- mende behager at henvende dem til Megler H. <em>Jursensen. Permanent</em> Committee'",
        "(//arr[@name='highlights']/arr/lst/str[@name='page'])[1]='PAGE1'",
        "(//arr[@name='highlights']/arr/lst/str[@name='page'])[2]='PAGE2'",
        "count(//arr[@name='regions']/lst)=2");
  }

  @Test
  public void testHyphenationResolved() throws Exception {
    SolrQueryRequest req = xmlQ("q", "\"faux espoir\"", "hl.weightMatches", "true");
    assertQ(req,
            "//str[@name='text'][1]/text()=\"— <em>Faux espoir</em>, mon vieil ami, <em>faux espoir</em> ! Je n'ai jamais même vu un seul des anciens compagnons de ses plaisirs. Lui\"",
            "count(//arr[@name='highlights']/arr/lst)=3",
            "(//arr[@name='highlights']/arr/lst/str[@name='text']/text())[1]='Faux espoir,'",
            "(//arr[@name='highlights']/arr/lst/str[@name='text']/text())[2]='faux es-'",
            "(//arr[@name='highlights']/arr/lst/str[@name='text']/text())[3]='poir'");
    req = xmlQ("q", "\"elle murmura\"", "hl.weightMatches", "true");
    assertQ(req,
            "//str[@name='text'][1]/text()=\"triste signe de tête avec lequel <em>elle murmura</em> :\"",
            "count(//arr[@name='highlights']/arr/lst)=2",
            "(//arr[@name='highlights']/arr/lst/str[@name='text']/text())[1]='elle mur-'",
            "(//arr[@name='highlights']/arr/lst/str[@name='text']/text())[2]='mura'");
  }
}
