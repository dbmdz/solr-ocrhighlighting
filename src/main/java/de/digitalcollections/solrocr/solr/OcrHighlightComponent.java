package de.digitalcollections.solrocr.solr;

import com.google.common.base.Strings;
import de.digitalcollections.solrocr.lucene.OcrHighlighter;
import de.digitalcollections.solrocr.util.PageCacheWarmer;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.lucene.search.Query;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.params.HighlightParams;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.common.util.SimpleOrderedMap;
import org.apache.solr.core.CloseHook;
import org.apache.solr.core.PluginInfo;
import org.apache.solr.core.SolrCore;
import org.apache.solr.handler.component.ResponseBuilder;
import org.apache.solr.handler.component.SearchComponent;
import org.apache.solr.handler.component.ShardRequest;
import org.apache.solr.handler.component.ShardResponse;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.search.QParser;
import org.apache.solr.search.QParserPlugin;
import org.apache.solr.search.QueryParsing;
import org.apache.solr.search.SyntaxError;
import org.apache.solr.util.SolrPluginUtils;
import org.apache.solr.util.plugin.PluginInfoInitialized;
import org.apache.solr.util.plugin.SolrCoreAware;

public class OcrHighlightComponent extends SearchComponent
    implements PluginInfoInitialized, SolrCoreAware {
  public static final String COMPONENT_NAME = "ocrHighlight";
  public static final String HL_RESPONSE_FIELD = "ocrHighlighting";

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
  public void prepare(ResponseBuilder rb) throws IOException {
    SolrParams params = rb.req.getParams();
    rb.doHighlights = params.getBool(HighlightParams.HIGHLIGHT, false);
    if (rb.doHighlights) {
      rb.setNeedDocList(true);
      String hlq = params.get(HighlightParams.Q);
      String hlparser =
          Stream.of(params.get(HighlightParams.QPARSER), params.get(QueryParsing.DEFTYPE))
              .filter(Objects::nonNull)
              .findFirst()
              .orElse(QParserPlugin.DEFAULT_QTYPE);
      if (hlq != null) {
        try {
          QParser parser = QParser.getParser(hlq, hlparser, rb.req);
          rb.setHighlightQuery(parser.getHighlightQuery());
        } catch (SyntaxError e) {
          throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, e);
        }
      }
    }
  }

  @Override
  public void inform(SolrCore core) {
    this.ocrHighlighter = new SolrOcrHighlighter();
    if ("true".equals(info.attributes.getOrDefault("enablePreload", "false"))) {
      PageCacheWarmer.enable(
          Integer.parseInt(info.attributes.getOrDefault("preloadReadSize", "32768")),
          Integer.parseInt(info.attributes.getOrDefault("preloadConcurrency", "8")));
    }

    // Shut down the cache warming threads after closing of the core
    core.addCloseHook(
        new CloseHook() {
          @Override
          public void preClose(SolrCore core) {}

          @Override
          public void postClose(SolrCore core) {
            PageCacheWarmer.getInstance().ifPresent(PageCacheWarmer::shutdown);
          }
        });
  }

  @Override
  public void process(ResponseBuilder rb) throws IOException {
    if (rb.doHighlights) {
      SolrQueryRequest req = rb.req;
      Query highlightQuery = rb.getHighlightQuery();
      if (highlightQuery == null) {
        if (rb.getQparser() != null) {
          try {
            highlightQuery = rb.getQparser().getHighlightQuery();
            rb.setHighlightQuery(highlightQuery);
          } catch (Exception e) {
            throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, e);
          }
        } else {
          highlightQuery = rb.getQuery();
          rb.setHighlightQuery(highlightQuery);
        }
      }

      if (highlightQuery != null) {
        NamedList<?> ocrHighlights =
            ocrHighlighter.doHighlighting(
                rb.getResults().docList,
                highlightQuery,
                req,
                rb.rsp.getResponseHeader().asShallowMap());
        if (ocrHighlights != null) {
          rb.rsp.add(HL_RESPONSE_FIELD, ocrHighlights);
        }
      }
      fixRegularHighlighting(rb);
    }
  }

  @Override
  public void modifyRequest(ResponseBuilder rb, SearchComponent who, ShardRequest sreq) {
    if (!rb.doHighlights) return;
    if ((sreq.purpose & ShardRequest.PURPOSE_GET_FIELDS) != 0) {
      sreq.purpose |= ShardRequest.PURPOSE_GET_HIGHLIGHTS;
      sreq.params.set(HighlightParams.HIGHLIGHT, "true");
    } else {
      sreq.params.set(HighlightParams.HIGHLIGHT, "false");
    }
  }

  /**
   * By default, the `HighlightComponent` will delegate to the query parser to pick the default set
   * of fields to be highlighted, in the case that the user doesn't submit a list herself.
   * Unfortunately this default set includes our OCR field, which in many cases is not compatible
   * with the regular highlighting approach since, from Solr's perspective, it doesn't include
   * plaintext. We thus set a customized list of highlighting fields, based on the default list, but
   * excluding any fields that are intended for OCR highlighting.
   */
  private void fixRegularHighlighting(ResponseBuilder rb) {
    ModifiableSolrParams params = new ModifiableSolrParams(rb.req.getParams());
    if (params.get("hl.fl") == null) {
      String ocrHlStr = params.get(OcrHighlightParams.OCR_FIELDS);
      Set<String> ocrHlFields = new HashSet<>();
      if (!Strings.isNullOrEmpty(ocrHlStr)) {
        ocrHlFields.addAll(Arrays.asList(ocrHlStr.split(",")));
      }
      String[] defaultHighlightFields =
          rb.getQparser() != null ? rb.getQparser().getDefaultHighlightFields() : null;
      if (defaultHighlightFields != null) {
        params.set(
            "hl.fl",
            Arrays.stream(defaultHighlightFields)
                .filter(fl -> !ocrHlFields.contains(fl))
                .collect(Collectors.joining(",")));
      }
      if (Strings.isNullOrEmpty(params.get("hl.fl"))) {
        params.set("hl", "off");
        rb.doHighlights = false;
      }
    }
    rb.req.setParams(params);
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  @Override
  public void finishStage(ResponseBuilder rb) {
    boolean setOcrHighlights =
        !Strings.isNullOrEmpty(rb.req.getParams().get(OcrHighlightParams.OCR_FIELDS, ""))
            && rb.stage == ResponseBuilder.STAGE_GET_FIELDS;
    if (setOcrHighlights) {
      final Object[] objArr = new NamedList.NamedListEntry[rb.resultIds.size()];
      for (ShardRequest sreq : rb.finished) {
        if ((sreq.purpose & ShardRequest.PURPOSE_GET_HIGHLIGHTS) == 0) continue;
        for (ShardResponse srsp : sreq.responses) {
          if (srsp.getException() != null) {
            // can't expect the highlight content if there was an exception for this request
            // this should only happen when using shards.tolerant=true
            continue;
          }
          NamedList<?> rspHeader =
              (NamedList<?>) srsp.getSolrResponse().getResponse().get("responseHeader");
          Boolean partialHls = (Boolean) rspHeader.get(OcrHighlighter.PARTIAL_OCR_HIGHLIGHTS);
          if (partialHls != null && partialHls) {
            rb.rsp.getResponseHeader().add(OcrHighlighter.PARTIAL_OCR_HIGHLIGHTS, true);
          }
          Object hl = srsp.getSolrResponse().getResponse().get(HL_RESPONSE_FIELD);
          SolrPluginUtils.copyNamedListIntoArrayByDocPosInResponse(
              (NamedList) hl, rb.resultIds, (Map.Entry<String, Object>[]) objArr);
        }
      }
      rb.rsp.add(
          HL_RESPONSE_FIELD,
          SolrPluginUtils.removeNulls(
              (Map.Entry<String, Object>[]) objArr, new SimpleOrderedMap<>()));
      fixRegularHighlighting(rb);
    }
  }
}
