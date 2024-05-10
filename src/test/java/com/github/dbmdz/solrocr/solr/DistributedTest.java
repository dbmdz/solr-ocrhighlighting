package com.github.dbmdz.solrocr.solr;

import com.carrotsearch.randomizedtesting.annotations.ThreadLeakFilters;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import org.apache.lucene.tests.util.QuickPatchThreadsFilter;
import org.apache.solr.BaseDistributedSearchTestCase;
import org.apache.solr.SolrIgnoredThreadsFilter;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.util.NamedList;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

@ThreadLeakFilters(
    defaultFilters = true,
    filters = {
      SolrIgnoredThreadsFilter.class,
      QuickPatchThreadsFilter.class,
      HlThreadsFilter.class
    })
public class DistributedTest extends BaseDistributedSearchTestCase {

  @Override
  public String getSolrHome() {
    return getFile("solr/distributed").getAbsolutePath();
  }

  @Override
  protected String getSolrXml() {
    return "solr.xml";
  }

  @BeforeClass
  public static void beforeClass() {
    System.setProperty("validateAfterInactivity", "200");
    System.setProperty("solr.httpclient.retries", "0");
    System.setProperty("distribUpdateSoTimeout", "5000");
    System.setProperty("solr.log.dir", "/tmp/debug-log-solr");
    // Needed since https://github.com/apache/solr/commit/16657ccab092
    System.setProperty("solr.install.dir", "./");
  }

  @Before
  public void before() throws Exception {
    del("*:*");
    index(
        "some_text",
        "Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor "
            + "incididunt ut labore et dolore magna aliqua. Ut enim ad minim veniam, quis nostrud "
            + "exercitation ullamco laboris nisi ut aliquip ex ea commodo consequat. Duis aute "
            + "irure dolor in reprehenderit in voluptate velit esse cillum dolore eu fugiat nulla "
            + "pariatur. Excepteur sint occaecat cupidatat non proident, sunt in culpa qui officia "
            + "deserunt mollit anim id est laborum.",
        "id",
        "1337");
    Path dataPath = Paths.get("src", "test", "resources", "data").toAbsolutePath();
    Path ocrPath = dataPath.resolve("alto.xml");
    index("ocr_text", ocrPath.toString(), "id", "31337");
    commit();
  }

  @Test
  public void testDistributedSearch() throws Exception {
    QueryResponse resp =
        query(
            "q", "svadag",
            "hl", "true",
            "hl.ocr.fl", "ocr_text",
            "hl.usePhraseHighlighter", "true",
            "df", "ocr_text",
            "hl.ctxTag", "ocr_line",
            "hl.ctxSize", "2",
            "hl.snippets", "10",
            "fl", "id,score");
    assertEquals(1, resp.getResults().getNumFound());
    // NOTE: the `query` method itself also validates the response against a non-sharded setup, so
    // we don't have to
    //       do a lot of assertions here, since the general case is already covered by the other
    // tests.
  }

  @Test
  public void testDistributedTimeout() throws Exception {
    QueryResponse resp =
        query(
            "q", "svadag",
            "hl", "true",
            "hl.ocr.fl", "ocr_text",
            "hl.usePhraseHighlighter", "true",
            "df", "ocr_text",
            "hl.ctxTag", "ocr_line",
            "hl.ctxSize", "2",
            "hl.snippets", "10",
            "hl.ocr.timeAllowed", "0",
            "fl", "id,score");
    assertEquals(1, resp.getResults().getNumFound());
    assertEquals(true, resp.getHeader().getBooleanArg("partialOcrHighlights"));
  }

  @Test
  public void testRegularHighlightingWorks() throws Exception {
    QueryResponse resp =
        query(
            "q",
            "\"commodo consequat\"",
            "hl",
            "true",
            "hl.fl",
            "some_text",
            "hl.weightMatches",
            "true",
            "df",
            "some_text",
            "fl",
            "id,score");
    assertEquals(1, resp.getResults().getNumFound());
    List<String> hls = resp.getHighlighting().get("1337").get("some_text");
    assertEquals(hls.size(), 1);
    assertEquals(
        hls.get(0),
        "Ut enim ad minim veniam, quis nostrud exercitation ullamco laboris nisi ut aliquip ex ea <em>commodo consequat</em>. Duis aute irure dolor in "
            + "reprehenderit in voluptate velit esse cillum dolore eu fugiat nulla pariatur. ");
  }

  @Test
  public void testCombinedHighlightingWorks() throws Exception {
    QueryResponse resp =
        query(
            "q",
            "\"commodo consequat\" svadag",
            "hl",
            "true",
            "defType",
            "edismax",
            "hl.weightMatches",
            "true",
            "qf",
            "some_text ocr_text",
            "fl",
            "id,score",
            "hl.ocr.fl",
            "ocr_text");
    assertEquals(2, resp.getResults().getNumFound());
    List<String> hls = resp.getHighlighting().get("1337").get("some_text");
    assertEquals(hls.size(), 1);
    assertEquals(
        hls.get(0),
        "Ut enim ad minim veniam, quis nostrud exercitation ullamco laboris nisi ut aliquip ex ea <em>commodo consequat</em>. Duis aute irure dolor in "
            + "reprehenderit in voluptate velit esse cillum dolore eu fugiat nulla pariatur. ");
    NamedList<?> ocrHls = (NamedList<?>) resp.getResponse().get("ocrHighlighting");
    assertEquals(1, ocrHls.size());
    assertEquals(1, ((NamedList<?>) ocrHls.get("31337")).size());
  }
}
