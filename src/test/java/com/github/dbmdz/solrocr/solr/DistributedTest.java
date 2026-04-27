package com.github.dbmdz.solrocr.solr;

import com.carrotsearch.randomizedtesting.annotations.ThreadLeakFilters;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import org.apache.lucene.tests.util.QuickPatchThreadsFilter;
import org.apache.solr.BaseDistributedSearchTestCase;
import org.apache.solr.SolrIgnoredThreadsFilter;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.core.CoreContainer;
import org.apache.solr.embedded.JettySolrRunner;
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
  public Path getSolrHome() {
    return getFile("solr/distributed");
  }

  @Override
  protected String getSolrXml() {
    return "solr.xml";
  }

  @Override
  protected JettySolrRunner createControlJetty() throws Exception {
    Path jettyHome = testDir.resolve("control");
    seedSolrHome(jettyHome);
    // Our custom distributed test home ships the collection config under `cores/collection1/conf`,
    // so we still need to materialize the matching core.properties file for the control node.
    Path coreDir = jettyHome.resolve("cores").resolve(DEFAULT_TEST_CORENAME);
    if (Files.notExists(coreDir.resolve(CORE_PROPERTIES_FILENAME))) {
      writeCoreProperties(coreDir, DEFAULT_TEST_CORENAME);
    }
    JettySolrRunner jetty =
        createJetty(jettyHome, null, null, getSolrConfigFile(), getSchemaFile());
    try {
      jetty.start();
      return jetty;
    } catch (Exception e) {
      CoreContainer container = jetty.getCoreContainer();
      if (container != null && !container.getCoreInitFailures().isEmpty()) {
        throw new IllegalStateException(
            "The CoreContainer is unavailable: " + container.getCoreInitFailures(), e);
      }
      throw e;
    }
  }

  @BeforeClass
  public static void beforeClass() {
    System.setProperty("validateAfterInactivity", "200");
    System.setProperty("solr.httpclient.retries", "0");
    System.setProperty("distribUpdateSoTimeout", "5000");
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
