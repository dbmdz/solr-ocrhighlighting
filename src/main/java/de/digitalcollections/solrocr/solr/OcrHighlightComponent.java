package de.digitalcollections.solrocr.solr;

import de.digitalcollections.solrocr.util.PageCacheWarmer;
import java.io.IOException;
import org.apache.lucene.search.Query;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.common.util.SimpleOrderedMap;
import org.apache.solr.core.CloseHook;
import org.apache.solr.core.PluginInfo;
import org.apache.solr.core.SolrCore;
import org.apache.solr.handler.component.ResponseBuilder;
import org.apache.solr.request.SolrQueryRequest;

public class OcrHighlightComponent extends org.apache.solr.handler.component.HighlightComponent {
  private PluginInfo info;
  private SolrOcrHighlighter ocrHighlighter;

  @Override
  public String getDescription() {
    return "OCR Highlighting";
  }

  @Override
  public void init(PluginInfo info) {
    this.info = info;
  }

  @Override
  public void inform(SolrCore core) {
    super.inform(core);
    this.ocrHighlighter = new SolrOcrHighlighter();

    // Shut down the cache warming threads after closing of the core
    core.addCloseHook(new CloseHook() {
      @Override
      public void preClose(SolrCore core) {
      }

      @Override
      public void postClose(SolrCore core) {
        PageCacheWarmer.shutdown();
      }
    });
  }

  @Override
  public void process(ResponseBuilder rb) throws IOException {
    if (rb.doHighlights) {
      SolrQueryRequest req = rb.req;
      String[] defaultHighlightFields = rb.getQparser() != null ? rb.getQparser().getDefaultHighlightFields() : null;
      Query highlightQuery = rb.getHighlightQuery();
      if(highlightQuery==null) {
        if (rb.getQparser() != null) {
          try {
            highlightQuery = rb.getQparser().getHighlightQuery();
            rb.setHighlightQuery( highlightQuery );
          } catch (Exception e) {
            throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, e);
          }
        } else {
          highlightQuery = rb.getQuery();
          rb.setHighlightQuery( highlightQuery );
        }
      }

      if( highlightQuery != null ) {
        NamedList ocrHighlights = ocrHighlighter.doHighlighting(
            rb.getResults().docList,
            highlightQuery,
            req, defaultHighlightFields, rb.rsp.getResponseHeader().asShallowMap());
        if (ocrHighlights != null) {
          rb.rsp.add(highlightingResponseField(), ocrHighlights);
        }
      }

      // Disable further highlighting if fields are not set to prevent the default highlighter
      // from highlighting our OCR fields, which will break.
      ModifiableSolrParams params = new ModifiableSolrParams(rb.req.getParams());
      if (params.get("hl.fl") == null) {
        params.set("hl", "false");
        rb.doHighlights = false;
        // Set the highlighting result to an empty list
        rb.rsp.add("highlighting", new SimpleOrderedMap<>());
      }
      rb.req.setParams(params);
    }
  }

  @Override
  protected String highlightingResponseField() {
    return "ocrHighlighting";
  }
}
