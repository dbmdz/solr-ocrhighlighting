package de.digitalcollections.solrocr.solr;

import com.google.common.collect.ImmutableMap;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.apache.solr.SolrTestCaseJ4;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.request.SolrQueryRequest;
import org.junit.BeforeClass;
import org.junit.Test;

public class HocrEscapedTest extends SolrTestCaseJ4 {
  @BeforeClass
  public static void beforeClass() throws Exception {
    initCore("solrconfig.xml", "schema.xml", "src/test/resources/solr", "hocr_escaped");

    Path ocrPath = Paths.get("src/test/resources/data/hocr_escaped.html");
    String text = new String(Files.readAllBytes(ocrPath), StandardCharsets.US_ASCII);
    assertU(adoc("ocr_text", text, "id", "42"));
    StringBuilder maskedText = new StringBuilder();
    maskedText.insert(0, "<!");
    maskedText.append(String.join("", Collections.nCopies(3064939, "-")));
    maskedText.append('>');
    maskedText.append(text.substring(3064942, 3098327));
    assertU(adoc("ocr_text", maskedText.toString(), "id", "84"));
    assertU(commit());
  }

  private static SolrQueryRequest xmlQ(String... extraArgs) throws Exception {
    Map<String, String> args = new HashMap<>(ImmutableMap.<String, String>builder()
        .put("hl", "true")
        .put("hl.fl", "ocr_text")
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
        "count(//lst[@name='ocrHighlighting']/lst[@name='42']/lst[@name='ocr_text']/arr/lst)=2",
        "//str[@name='text'][1]/text()='lung. Ganz vorn lagen die drei mittelmäßigen, aber ſehr populären "
            + "Jlluſtrationen zu Lermontoffs „Dämon“: die Verführung <em>Tamaras</em> durch den Dämon, ihre "
            + "Hingabe an ihn, ihr Tod durch ihn. Fenia wies mit dem Muff darauf hin.'",
        "//arr[@name='regions'][1]/lst/int[@name='ulx']/text()=146",
        "//arr[@name='highlights']/arr/lst[1]/int[@name='ulx']/text()=361"
    );
  }

  @Test
  public void testWeightMatches() throws Exception {
    SolrQueryRequest req = xmlQ("q", "\"Verführung Tamaras\"", "hl.weightMatches", "true");
    assertQ(req,
        "count(//lst[@name='ocrHighlighting']/lst[@name='42']/lst[@name='ocr_text']/arr/lst)=1",
        "//str[@name='text'][1]/text()='lung. Ganz vorn lagen die drei mittelmäßigen, aber ſehr populären "
            + "Jlluſtrationen zu Lermontoffs „Dämon“: die <em>Verführung Tamaras</em> durch den Dämon, ihre "
            + "Hingabe an ihn, ihr Tod durch ihn. Fenia wies mit dem Muff darauf hin.'",
        "//arr[@name='regions'][1]/lst/int[@name='ulx']/text()=146",
        "//arr[@name='highlights']/arr/lst[1]/int[@name='ulx']/text()=83");
  }

  @Test
  public void testSubsectionHighlighting() throws Exception {
    SolrQueryRequest req = xmlQ("q", "\"brütenden Sonnenwärme\"", "hl.weightMatches", "true");
    assertQ(req,
        "count(//lst[@name='ocrHighlighting']/lst)=2",
        "//lst[@name='ocrHighlighting']/lst[@name='84']//arr[@name='snippets']/lst/str[@name='text']/text()='"
            + "glückſeligen Klang ihres gedämpften Lachens und mit dem Eindru> der: <em>brütenden Sonnenwärme</em> um "
            + "uns. Wer will abwägen, wie unendlich zufällig, wie rein äußerlich bedingt es vielleicht iſt, wenn mir "
            + "bei dieſer Erinnerung'",
        "//lst[@name='ocrHighlighting']/lst[@name='42']//arr[@name='snippets']/lst/str[@name='text']/text()='"
            + "glückſeligen Klang ihres gedämpften Lachens und mit dem Eindru> der: <em>brütenden Sonnenwärme</em> um "
            + "uns. Wer will abwägen, wie unendlich zufällig, wie rein äußerlich bedingt es vielleicht iſt, wenn mir "
            + "bei dieſer Erinnerung'");
    req = xmlQ("q", "\"Volfslieder heller von den Lippen\"", "hl.weightMatches", "true");
    assertQ(req,
        "count(//lst[@name='ocrHighlighting']/lst)=1");
  }

  @Test
  public void testPageNumberAtBeginningOfPage() throws Exception {
    SolrQueryRequest req = xmlQ("q", "\"peramentvollere Glänzendere\"", "hl.weightMatches", "true");
    assertQ(req, "//lst[@name='ocrHighlighting']//str[@name='page']/text()='page_109'");
  }

  @Test
  public void testOverlappingMatches() throws Exception {
    SolrQueryRequest req = xmlQ("q", "\"pirate vessel\"~10", "hl.weightMatches", "true",
                                "hl.ocr.contextSize", "0");
    assertQ(
        req,
        "//lst[@name='ocrHighlighting']//str[@name='text']/text()='<em>pirates hove their vessel that the other pirates</em> had trashed'",
        "//lst[@name='ocrHighlighting']//arr[@name='highlights']//str[@name='text']/text()='pirates hove their vessel that the other pirates'");
  }

  @Test
  public void testAbsoluteHighlightRegions() throws Exception {
    SolrQueryRequest req = xmlQ("q", "Verführung", "hl.ocr.absoluteHighlights", "true");
    assertQ(req,
            "//arr[@name='regions'][1]/lst/int[@name='ulx']/text()=146",
            "//arr[@name='highlights']/arr/lst[1]/int[@name='ulx']/text()=229");
  }

  @Test
  public void testLimitBlockHonored() throws Exception {
    SolrQueryRequest req = xmlQ("q", "Japan", "hl.ocr.absoluteHighlights", "true");
    assertQ(req,
            "(//arr[@name='snippets']/lst/str[@name='text']/text())[3]='object too hastily, in addition to the facts already stated it ought to be remarked, that Kunnpfer describes the coast of<em>Japan</em>'");
  }

  @Test
  public void testAccidentalMerge() throws Exception {
    SolrQueryRequest req = xmlQ("q", "Robinson");
    assertQ(req, "count(//arr[@name='highlights']/arr)=2");
  }

  @Test
  public void testMultiPageSnippet() throws Exception {
    SolrQueryRequest req = xmlQ("q", "\"max werner hochzeit\"~10", "hl.ocr.limitBlock", "none", "hl.weightMatches", "true");
    assertQ(
        req,
        "//str[@name='text'][1]/text()='einer Verwandten ihres zukünftigen Mannes, die im Auslande ſtudiert und kürzlich promoviert habe. Tief im Winter, Mitte Januar, reiſte <em>Max Werner zur Hochzeit</em> ſeiner Schweſter in die ruſſiſche Provinz. Dort, auf dem Gut von deren Freunden, wo eine Un- menge fremder Gäſte untergebracht waren, ſah er mitten'",
        "(//arr[@name='highlights']/arr/lst/str[@name='page'])[1]='page_31'",
        "(//arr[@name='highlights']/arr/lst/str[@name='page'])[2]='page_32'",
        "count(//arr[@name='regions']/lst)=2");
  }

  @Test
  public void testMergedRegionExceedsContext() throws Exception {
    SolrQueryRequest req = xmlQ("q", "\"lord's prayer\"", "hl.weightMatches", "true");
    assertQ(req,
            "count(//arr[@name='regions']/lst)=1",
            "//str[@name='text'][1]/text()=\"Witches are reported (amongst many other hellish observations, whereby "
            + "they obh'ge them\u00ADselves to Satan) to say the <em>Lord's prayer</em> back\u00ADwards. "
            + "Are there not many, who, though they do not. pronounce the syllables of the <em>Lord's "
            + "prayer</em> retrograde (their discretion will not suf\u00ADfer them to be betrayed to such a "
            + "nonsense sin), yet they transpose it in effect, desiring their\"");
  }

  @Test
  public void testHyphenationIsResolved() throws Exception {
    SolrQueryRequest req = xmlQ("q", "\"themselves to Satan\"", "hl.weightMatches", "true");
    assertQ(req, "count(//arr[@name='regions']/lst)=1");
  }

  @Test
  public void testHighlightTextUnescaped() throws Exception {
    SolrQueryRequest req = xmlQ("q", "\"alſo beſſer\"", "hl.weightMatches", "true");
    assertQ(req, "//arr[@name='highlights']//str[@name='text']/text()='alſo beſſer,'");
  }

}