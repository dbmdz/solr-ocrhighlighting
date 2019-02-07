package org.mdz.search.solrocr.solr;

import java.io.IOException;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.apache.lucene.analysis.util.ResourceLoader;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.uhighlight.UnifiedHighlighter;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.SolrException.ErrorCode;
import org.apache.solr.common.params.HighlightParams;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.common.util.SimpleOrderedMap;
import org.apache.solr.core.PluginInfo;
import org.apache.solr.core.SolrCore;
import org.apache.solr.core.SolrResourceLoader;
import org.apache.solr.highlight.UnifiedSolrHighlighter;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.search.DocList;
import org.apache.solr.util.plugin.PluginInfoInitialized;
import org.mdz.search.solrocr.formats.OcrBlock;
import org.mdz.search.solrocr.formats.OcrFormat;
import org.mdz.search.solrocr.formats.OcrPassageFormatter;
import org.mdz.search.solrocr.formats.OcrSnippet;
import org.mdz.search.solrocr.lucene.ExternalFieldLoader;
import org.mdz.search.solrocr.lucene.OcrHighlighter;

public class SolrOcrHighlighter extends UnifiedSolrHighlighter {
  private final ResourceLoader resourceLoader;

  private ExternalFieldLoader fieldLoader;
  private OcrFormat ocrFormat;
  private List<String> ocrFieldNames;

  public SolrOcrHighlighter() {
    this.resourceLoader = new SolrResourceLoader();
  }

  @Override
  public void init(PluginInfo info) {
    super.init(info);
    String formatClsName = info.attributes.get("ocrFormat");
    if (formatClsName == null) {
      throw new SolrException(
          ErrorCode.FORBIDDEN,
          "Please configure your OCR format with the `ocrFormat` attribute on <highlighting>. "
        + "Refer to the org.mdz.search.solrocr.formats package for available formats.");
    }
    this.ocrFormat = SolrCore.createInstance(formatClsName, OcrFormat.class, null, null, resourceLoader);

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
      fieldLoader = SolrCore.createInstance(
          fieldLoaderInfo.className, ExternalFieldLoader.class,null, null, resourceLoader);
      if (fieldLoader instanceof PluginInfoInitialized) {
        ((PluginInfoInitialized) fieldLoader).init(fieldLoaderInfo);
      }
    }
  }

  @Override
  public NamedList<Object> doHighlighting(DocList docs, Query query, SolrQueryRequest req, String[] defaultFields)
      throws IOException {
    // Copied from superclass
    // - *snip* -
    final SolrParams params = req.getParams();
    if (!isHighlightingEnabled(params))
      return null;
    int[] docIDs = toDocIDs(docs);
    String[] keys = getUniqueKeys(req.getSearcher(), docIDs);
    // - *snap* -

    // query-time parameters
    String[] ocrFieldNames = getOcrHighlightFields(query, req, defaultFields);
    String[] regularFieldNames = Stream.of(getHighlightFields(query, req, defaultFields))
        .filter(f -> Arrays.binarySearch(ocrFieldNames, f) < 0)
        .toArray(String[]::new);

    int[] maxPassagesRegular = getMaxPassages(regularFieldNames, params);
    int[] maxPassagesOcr = getMaxPassages(ocrFieldNames, params);

    // Highlight non-OCR fields
    Map<String, String[]> regularSnippets = null;
    if (regularFieldNames.length > 0) {
      UnifiedHighlighter regularHighlighter = getHighlighter(req);
      regularSnippets = regularHighlighter.highlightFields(regularFieldNames, query, docIDs, maxPassagesRegular);
    }

    Map<String, OcrSnippet[][]> ocrSnippets = null;
    // Highlight OCR fields
    if (ocrFieldNames.length > 0) {
      OcrHighlighter ocrHighlighter = new OcrHighlighter(
          req.getSearcher(), req.getSchema().getIndexAnalyzer(), fieldLoader);
      ocrFormat.setBreakParameters(
          OcrBlock.valueOf(params.get("hl.ocr.contextBlock", "line").toUpperCase()),
          params.getInt("hl.ocr.contextSize", 2));
      BreakIterator ocrBreakIterator = ocrFormat.getBreakIterator();
      OcrPassageFormatter ocrFormatter = ocrFormat.getPassageFormatter(
          OcrBlock.valueOf(params.get("hl.ocr.limitBlock", "block").toUpperCase()),
          params.get("hl.tag.pre", "<em>"), params.get("hl.tag.post", "</em>"));
      ocrSnippets = ocrHighlighter.highlightOcrFields(
          ocrFieldNames, query, docIDs, maxPassagesOcr, ocrBreakIterator, ocrFormatter);
    }

    // Assemble output data
    NamedList<Object> out = new NamedList<>();
    if (regularSnippets != null) {
      out.addAll(this.encodeSnippets(keys, regularFieldNames, regularSnippets));
    }
    if (ocrSnippets != null) {
      this.addOcrSnippets(out, keys, ocrFieldNames, ocrSnippets);
    }
    return out;
  }

  private int[] getMaxPassages(String[] fieldNames, SolrParams params) {
    int[] maxPassages = new int[fieldNames.length];
    for (int i = 0; i < fieldNames.length; i++) {
      maxPassages[i] = params.getFieldInt(fieldNames[i], HighlightParams.SNIPPETS, 1);
    }
    return maxPassages;
  }

  private void addOcrSnippets(NamedList<Object> out, String[] keys, String[] ocrFieldNames,
                              Map<String, OcrSnippet[][]> ocrSnippets) {
    for (int k=0; k < keys.length; k++) {
      String docId = keys[k];
      SimpleOrderedMap docMap = (SimpleOrderedMap) out.get(docId);
      if (docMap == null) {
        docMap = new SimpleOrderedMap();
        out.add(docId, docMap);
      }
      for (String fieldName : ocrFieldNames) {
        OcrSnippet[] snips = ocrSnippets.get(fieldName)[k];
        NamedList[] outSnips = new SimpleOrderedMap[snips.length];
        for (int s = 0; s < snips.length; s++) {
          OcrSnippet snip = snips[s];
          outSnips[s] = snip.toNamedList();
        }
        docMap.add(fieldName, outSnips);
      }
    }
  }

  /** Obtain all fields among the requested fields that contain OCR data. */
  private String[] getOcrHighlightFields(Query query, SolrQueryRequest req, String[] defaultFields) {
    return Arrays.stream(getHighlightFields(query, req, defaultFields))
        .distinct()
        .filter(ocrFieldNames::contains)
        .toArray(String[]::new);
  }
}
