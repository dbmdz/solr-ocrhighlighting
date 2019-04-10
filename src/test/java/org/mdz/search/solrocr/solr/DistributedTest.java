package org.mdz.search.solrocr.solr;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.apache.solr.BaseDistributedSearchTestCase;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.junit.BeforeClass;
import org.junit.Test;

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
  }

  @Test
  public void testDistributedSearch() throws Exception {
    del("*:*");
    index(
        "some_text",
        "Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor "
        + "incididunt ut labore et dolore magna aliqua. Ut enim ad minim veniam, quis nostrud "
        + "exercitation ullamco laboris nisi ut aliquip ex ea commodo consequat. Duis aute "
        + "irure dolor in reprehenderit in voluptate velit esse cillum dolore eu fugiat nulla "
        + "pariatur. Excepteur sint occaecat cupidatat non proident, sunt in culpa qui officia "
        + "deserunt mollit anim id est laborum.", "id", "1337");
    Path dataPath = Paths.get("src", "test", "resources", "data").toAbsolutePath();
    Path ocrPath = dataPath.resolve("alto_escaped.xml");
    index(
        "ocr_text", new String(Files.readAllBytes(ocrPath), StandardCharsets.US_ASCII),
        "id", "31337");
    commit();

    QueryResponse resp = query(
        "q", "svadag",
        "hl", "true",
        "hl.fields", "ocr_text",
        "hl.usePhraseHighlighter", "true",
        "df", "ocr_text",
        "hl.ctxTag", "ocr_line",
        "hl.ctxSize", "2",
        "hl.snippets", "10",
        "fl", "id,score");
    assertEquals(1, resp.getResults().getNumFound());
    // NOTE: the `query` method itself also validates the response against a non-sharded setup, so we don't have to
    //       do a lot of assertions here, since the general case is already covered by the other tests.
  }
}
