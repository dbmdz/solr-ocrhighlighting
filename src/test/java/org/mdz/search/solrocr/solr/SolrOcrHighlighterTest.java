package org.mdz.search.solrocr.solr;

import org.apache.solr.SolrTestCaseJ4;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.handler.component.HighlightComponent;
import org.apache.solr.request.SolrQueryRequest;
import org.junit.BeforeClass;
import org.junit.Test;

public class SolrOcrHighlighterTest extends SolrTestCaseJ4 {
  @BeforeClass
  public static void beforeClass() throws Exception {
    initCore("solrconfig.xml", "schema.xml", "src/test/resources/solr", "minimal");

    HighlightComponent hlComp = (HighlightComponent) h.getCore().getSearchComponent("highlight");
    assertTrue("wrong highlighter: " + hlComp.getHighlighter().getClass(),
               hlComp.getHighlighter() instanceof SolrOcrHighlighter);

    assertU(adoc(
        "some_text",
        "Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor "
      + "incididunt ut labore et dolore magna aliqua. Ut enim ad minim veniam, quis nostrud "
      + "exercitation ullamco laboris nisi ut aliquip ex ea commodo consequat. Duis aute "
      + "irure dolor in reprehenderit in voluptate velit esse cillum dolore eu fugiat nulla "
      + "pariatur. Excepteur sint occaecat cupidatat non proident, sunt in culpa qui officia "
      + "deserunt mollit anim id est laborum.", "id", "1337"));
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
  public void testRegularHighlight() throws Exception {
    SolrQueryRequest req = xmlQ(
        "q", "exercitation", "hl", "true", "hl.fields", "some_text", "hl.usePhraseHighlighter", "true", "df", "some_text");
    assertQ(req,
        "//lst[@name='highlighting']/lst[@name='1337']/arr[@name='some_text']/str/text()="
       + "'Ut enim ad minim veniam, quis nostrud <em>exercitation</em> ullamco laboris nisi ut aliquip ex ea commodo consequat. '");
  }
}