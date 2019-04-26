package org.mdz.search.solrocr.solr;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import org.apache.lucene.search.Query;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.SolrException.ErrorCode;
import org.apache.solr.common.params.HighlightParams;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.core.PluginInfo;
import org.apache.solr.core.SolrCore;
import org.apache.solr.handler.component.ResponseBuilder;
import org.apache.solr.request.SolrQueryRequest;
import org.mdz.search.solrocr.formats.OcrFormat;
import org.mdz.search.solrocr.lucene.fieldloader.ExternalFieldLoader;

public class HighlightComponent extends org.apache.solr.handler.component.HighlightComponent {
  private PluginInfo info;
  private OcrFormat ocrFormat;
  private ArrayList<String> ocrFieldNames;
  private ExternalFieldLoader fieldLoader;
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
    String formatClsName = info.attributes.get("ocrFormat");
    if (formatClsName == null) {
      throw new SolrException(
          ErrorCode.FORBIDDEN,
          "Please configure your OCR format with the `ocrFormat` attribute on <highlighting>. "
          + "Refer to the org.mdz.search.solrocr.formats package for available formats.");
    }
    this.ocrFormat = SolrCore.createInstance(
        formatClsName, OcrFormat.class, null, null, core.getResourceLoader());

    NamedList<String> ocrFieldInfo = (NamedList) info.initArgs.get("ocrFields");
    if (ocrFieldInfo == null) {
      throw new SolrException(
          ErrorCode.FORBIDDEN,
          "Please define the fields that OCR highlighting should apply to in a ocrFields list in your solrconfig.xml. "
          + "Example: <lst name\"ocrFields\"><str>ocr_text</str></lst>");
    }
    this.ocrFieldNames = new ArrayList<>();
    ocrFieldInfo.forEach((k, fieldName) -> ocrFieldNames.add(fieldName));

    PluginInfo fieldLoaderInfo = info.getChild("fieldLoader");
    if (fieldLoaderInfo != null) {
      this.fieldLoader = core.createInitInstance(
          fieldLoaderInfo, ExternalFieldLoader.class, "", null);
    }
    this.ocrHighlighter = new SolrOcrHighlighter(fieldLoader, ocrFormat, ocrFieldNames);
  }

  @Override
  public void process(ResponseBuilder rb) throws IOException {
    if (rb.doHighlights) {
      SolrQueryRequest req = rb.req;
      SolrParams params = req.getParams();
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

      ModifiableSolrParams ocrParams = new ModifiableSolrParams(params);
      String[] hlFields = params.getParams(HighlightParams.FIELDS);
      if (hlFields != null) {
        ocrParams.set(
            HighlightParams.FIELDS,
            Arrays.stream(hlFields).filter(ocrFieldNames::contains).toArray(String[]::new));
      }

      if( highlightQuery != null ) {
        req.setParams(ocrParams);
        NamedList ocrHighlights = ocrHighlighter.doHighlighting(
            rb.getResults().docList,
            highlightQuery,
            req, defaultHighlightFields);
        if (ocrHighlights != null) {
          rb.rsp.add(highlightingResponseField(), ocrHighlights);
        }
      }
    }
  }

  @Override
  protected String highlightingResponseField() {
    return "ocrHighlighting";
  }
}
