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
    ocrPath = Paths.get("src/test/resources/data/alto_float.xml");
    assertU(adoc("ocr_text", ocrPath.toString(), "id", "44"));
    String multiColumnPointer =
        "src/test/resources/data/alto_columns/alto1.xml[179626:179968,179973:180491,180496:180820,"
            + "180825:181162,181167:191088,191093:214687,214692:261719,261724:267933,267938:310387,"
            + "310392:352814]+src/test/resources/data/alto_columns/alto2.xml[1997:8611,8616:13294,13299:15243,15248:50042,"
            + "50047:53793,53798:73482,73487:86667,86672:94241,94246:99808,99813:103087,103092:115141,115146:116775,"
            + "116780:122549,122554:149762,149767:192789,192794:193502]";
    assertU(adoc("ocr_text", multiColumnPointer, "id", "96"));
    ocrPath = Paths.get("src/test/resources/data/alto_nospace.xml");
    assertU(adoc("ocr_text", ocrPath.toString(), "id", "47"));
    ocrPath = Paths.get("src/test/resources/data/chronicling_america.xml");
    assertU(adoc("ocr_text", ocrPath.toString(), "id", "72"));
    assertU(commit());
  }

  private static SolrQueryRequest xmlQ(String... extraArgs) {
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
  public void testAltoWithFloat() throws Exception {
    SolrQueryRequest req = xmlQ("q", "mighty");
    assertQ(req,
            "count(//lst[@name='ocrHighlighting']/lst[@name='44']/lst[@name='ocr_text']/arr/lst)=1",
            "//arr[@name='highlights']/arr/lst[1]/int[@name='ulx']/text()=524"
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
        "count(//arr[@name='pages']/lst)=2",
        "(//arr[@name='pages']/lst/str[@name='id'])[1]/text()='PAGE1'",
        "(//arr[@name='pages']/lst/int[@name='width'])[1]/text()='9148'",
        "(//arr[@name='pages']/lst/int[@name='height'])[1]/text()='10928'",
        "(//arr[@name='pages']/lst/str[@name='id'])[2]/text()='PAGE2'",
        "(//arr[@name='pages']/lst/int[@name='width'])[2]/text()='2092'",
        "(//arr[@name='pages']/lst/int[@name='height'])[2]/text()='3850'",
        "(//arr[@name='regions']/lst/int[@name='pageIdx'])[1]='0'",
        "(//arr[@name='regions']/lst/int[@name='pageIdx'])[2]='1'",
        "(//arr[@name='highlights']/arr/lst/int[@name='parentRegionIdx'])[1]='0'",
        "(//arr[@name='highlights']/arr/lst/int[@name='parentRegionIdx'])[2]='1'",
        "count(//arr[@name='regions']/lst)=2");
  }

  @Test
  public void testHyphenationResolved() throws Exception {
    SolrQueryRequest req = xmlQ("q", "\"faux espoir\"", "hl.weightMatches", "true");
    assertQ(req,
            "//str[@name='text'][1]/text()=\"— <em>Faux espoir</em>, mon vieil ami, <em>faux espoir</em> ! Je n'ai jamais même vu un seul des anciens compagnons de ses plaisirs. Luimême avait renoncé à toute autre société\"",
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

  @Test
  public void testMultipleColumns() {
    SolrQueryRequest req = xmlQ("q", "\"Hans  Bockel\"", "hl.weightMatches", "true");
    // We had a bug that resulted in multiple regions here, this assert just tests for that
    assertQ(
        req,
        "count(//arr[@name='regions']/lst)=1",
        "contains(//arr[@name='regions']/lst/str[@name='text']/text(), 'charrette de <em>Hans Bockel</em> qui passe,')"
    );
    // Phrase spanning multiple columns
    req = xmlQ("q", "\"moineau qui possède\"", "hl.weightMatches", "true");
    assertQ(
        req,
        "count(//arr[@name='regions']/lst)=2",
        "contains(//arr[@name='regions']/lst[1]/str[@name='text']/text(), 'histoire du <em>moineau</em>')",
        "contains(//arr[@name='regions']/lst[2]/str[@name='text'], '<em>qui possède</em> deux fois')",
        "count(//arr[@name='highlights']/arr)=1",
        "count(//arr[@name='highlights']/arr/lst)=2",
        "//arr[@name='highlights']/arr/lst[1]/int[@name='parentRegionIdx']/text()='0'",
        "//arr[@name='highlights']/arr/lst[1]/str[@name='text']/text()='moineau'",
        "//arr[@name='highlights']/arr/lst[2]/int[@name='parentRegionIdx']/text()='1'",
        "//arr[@name='highlights']/arr/lst[2]/str[@name='text']/text()='qui possède'"
    );
  }

  @Test
  public void testDehyphenation() {
    SolrQueryRequest req = xmlQ("q", "ocr_text:\"au bureau en qualité\"");
    assertQ(
        req,
        "count(//arr[@name='regions'])=1",
        "contains(//arr[@name='snippets']/lst/str[@name='text'], '<em>qualité</em>')",
        "contains(//arr[@name='regions']/lst/str[@name='text'], '<em>qualité</em>')");
  }

  @Test
  public void testAlignSpans() {
    String regionUnaligned = "Les seuls députés qui aient voté pour l'instruction primaire, gratuite et obligatoire, "
        + "combattue par M. de Parieu, vice-<em>président</em> du conseil d'Etat, sont MM. Belmont et Carnet, "
        + "Chevandier de <em>Valdrôme</em>. Favre (Jules). Garnier-Pcgès, Glais-B.'zoin, Guérouit. Havin, Hôr"
        + ".on, Magnin, Marie, Le";
    String regionAligned = "Les seuls députés qui aient voté pour l'instruction primaire, gratuite et obligatoire, "
        + "combattue par M. de Parieu, <em>vice-président</em> du conseil d'Etat, sont MM. Belmont et Carnet, "
        + "Chevandier de <em>Valdrôme.</em> Favre (Jules). Garnier-Pcgès, Glais-B.'zoin, Guérouit. Havin, Hôr"
        + ".on, Magnin, Marie, Le";
    SolrQueryRequest req = xmlQ("q", "ocr_text:(président OR Valdrôme)", "hl.ocr.pageId", "P3");
    assertQ(req, "(//arr[@name='regions']/lst/str[@name='text'])[1]/text()=\"" + regionUnaligned + "\"");
    req = xmlQ("q", "ocr_text:(président OR Valdrôme)", "hl.ocr.pageId", "P3", "hl.ocr.alignSpans", "true");
    assertQ(req, "//arr[@name='regions']/lst/str[@name='text']/text()=\"" + regionAligned + "\"");
  }

  @Test
  public void testOverzealousMerging() {
    SolrQueryRequest req = xmlQ("q", "ocr_text:\"au bureau en qualité\"");
    assertQ(req, "count(//arr[@name='highlights']/arr)=4");
  }

  public void testConsistentSpaceHandling() {
    String text = "Die Zahl derer, welche jene Schreckens: zeit <em>mit Augen ſahen, in welcher Zittau</em>"
        + ", <em>im Gefolge</em> des ſiebenjährigen Krieges, den 23. Juli 1757, auf die "
        + "ſchre>li<ſte Art zerſtört ward, kann zwar nur noch klein";
    SolrQueryRequest req = xmlQ(
        "q", "ocr_text:\"mit Augen ſahen, in welcher Zittau\" OR ocr_text:\"im Gefolge\"",
        "hl.weightMatches", "true");
    assertQ(
        req,
        "//arr[@name='snippets']/lst/str[@name='text']/text()=\"" + text + "\"",
        "//arr[@name='regions']/lst/str[@name='text']/text()=\"" + text + "\""
    );
  }

  public void testAlternatives() {
    SolrQueryRequest req = xmlQ("q", "ocr_text:\"YoB Greene pUlcohased\"");
    assertQ(req, "count(//arr[@name='snippets'])='1'");
    req = xmlQ("q", "ocr_text:\"OB Greene purchased\"");
    assertQ(req, "count(//arr[@name='snippets'])='1'");
    req = xmlQ("q", "ocr_text:\"YoB Greene purchased\"");
    assertQ(req, "count(//arr[@name='snippets'])='1'");
  }

  public void testHyphenText() {
    Path ocrPath = Paths.get("src/test/resources/data/alto_hyphen.xml");
    assertU(adoc("ocr_text", ocrPath.toString(), "id", "47371"));
    assertU(commit());
    SolrQueryRequest req = xmlQ("q", "ocr_text:\"able letter indeed it did mr\"", "hl.weightMatches", "true");
    assertQ(
        req,
        "//arr[@name='snippets']/lst/str[@name='text']/text()='fronts him. Tilden and Mis Letter. \" An <em>able letter indeed. It did Mr</em>. Tilden great credit. I read it carefully several times. Ne ; I havent read the Cin-'",
       "//arr[@name='regions']/lst/str[@name='text']/text()='fronts him. Tilden and Mis Letter. \" An <em>able letter indeed. It did Mr</em>. Tilden great credit. I read it carefully several times. Ne ; I havent read the Cin-'");
  }

  public void testWackyHyphenation() {
    Path ocrPath = Paths.get("src/test/resources/data/alternatives_bug.xml");
    assertU(adoc("ocr_text", ocrPath.toString(), "id", "47371"));
    ocrPath = Paths.get("src/test/resources/data/alternatives_bug.html");
    assertU(adoc("ocr_text", ocrPath.toString(), "id", "47372"));
    assertU(commit());
    SolrQueryRequest req = xmlQ("q", "ocr_text:\"about\"", "hl.weightMatches", "true");
    assertQ(
        req,
        "contains(((//lst[@name='47371']//arr[@name='snippets'])[1]/lst/str[@name='text'])[1]/text(), 'p- I Lucky Day')",
        "contains(((//lst[@name='47371']//arr[@name='snippets'])[1]//arr[@name='regions']/lst/str[@name='text'])[1]/text(), 'p- I Lucky Day')",
        "contains(((//lst[@name='47372']//arr[@name='snippets'])[1]/lst/str[@name='text'])[1]/text(), 'p- I Lucky Day')",
        "contains(((//lst[@name='47372']//arr[@name='snippets'])[1]//arr[@name='regions']/lst/str[@name='text'])[1]/text(), 'p- I Lucky Day')"
    );
  }

  public void testNoMatch() {
    Path ocrPath = Paths.get("src/test/resources/data/nomatch.html");
    assertU(adoc("ocr_text", ocrPath.toString(), "id", "47371"));
    assertU(commit());
    SolrQueryRequest req = xmlQ("q", "ocr_text:\"warfare is not checked governors and\"", "hl.weightMatches", "true");
    assertQ(req, "count(//arr[@name='snippets'])=1");
  }

  public void testMissingClosing() {
    Path ocrPath = Paths.get("src/test/resources/data/missing_closing.xml");
    assertU(adoc("ocr_text", ocrPath.toString(), "id", "47371"));
    assertU(commit());
    SolrQueryRequest req = xmlQ("q", "ocr_text:\"as to details\"", "hl.weightMatches", "true");
    assertQ(req, "contains(((//lst[@name='47371']//arr[@name='snippets'])[1]/lst/str[@name='text'])[2]/text(), '<em>details,</em>')");
  }
}
