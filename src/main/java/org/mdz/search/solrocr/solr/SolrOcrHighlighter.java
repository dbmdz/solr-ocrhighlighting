package org.mdz.search.solrocr.solr;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.BreakIterator;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.uhighlight.UnifiedHighlighter.HighlightFlag;
import org.apache.solr.common.params.HighlightParams;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.common.util.SimpleOrderedMap;
import org.apache.solr.highlight.UnifiedSolrHighlighter;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.search.DocList;
import org.mdz.search.solrocr.formats.OcrBlock;
import org.mdz.search.solrocr.formats.OcrFormat;
import org.mdz.search.solrocr.formats.OcrPassageFormatter;
import org.mdz.search.solrocr.lucene.OcrHighlighter;
import org.mdz.search.solrocr.lucene.fieldloader.ExternalFieldLoader;
import org.mdz.search.solrocr.util.OcrHighlightResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SolrOcrHighlighter extends UnifiedSolrHighlighter {

  private static final Logger LOGGER = LoggerFactory.getLogger(SolrOcrHighlighter.class);

  public static final String NO_WEIGHT_MATCHES_SUPPORT_MSG =
      "OCR highlighting in external UTF-8 files does not support hl.weightMatches, classic highlighting approach will "
    + "be used instead. Switch to escaped ASCII or UTF-16 to avoid this.";

  private ExternalFieldLoader fieldLoader;
  private OcrFormat ocrFormat;
  private List<String> ocrFieldNames;


  public SolrOcrHighlighter(ExternalFieldLoader fieldLoader, OcrFormat ocrFormat,
                            List<String> ocrFieldNames) {
    this.fieldLoader = fieldLoader;
    this.ocrFormat = ocrFormat;
    this.ocrFieldNames = ocrFieldNames;
  }

  @Override
  public NamedList<Object> doHighlighting(DocList docs, Query query, SolrQueryRequest req, String[] defaultFields)
      throws IOException {
    // Copied from superclass
    // - *snip* -
    final SolrParams params = req.getParams();
    if (!isHighlightingEnabled(params)) {
      return null;
    }
    if (docs.size() == 0) {
      return new SimpleOrderedMap<>();
    }
    int[] docIDs = toDocIDs(docs);
    String[] keys = getUniqueKeys(req.getSearcher(), docIDs);
    // - *snap* -

    // query-time parameters
    String[] ocrFieldNames = getOcrHighlightFields(query, req, defaultFields);
    int[] maxPassagesOcr = getMaxPassages(ocrFieldNames, params);

    Map<String, String> highlightFieldWarnings = new HashMap<>();
    OcrHighlightResult[] ocrSnippets = null;
    // Highlight OCR fields
    if (ocrFieldNames.length > 0) {
      OcrHighlighter ocrHighlighter = new OcrHighlighter(
          req.getSearcher(), req.getSchema().getIndexAnalyzer(), fieldLoader, req.getParams());
      if (fieldLoader.getCharset() == StandardCharsets.UTF_8) {
        Arrays.stream(ocrFieldNames)
            .filter(f -> ocrHighlighter.getFlags(f).contains(HighlightFlag.WEIGHT_MATCHES))
            .forEach(field -> highlightFieldWarnings.put(field, NO_WEIGHT_MATCHES_SUPPORT_MSG));
      }
      BreakIterator ocrBreakIterator = ocrFormat.getBreakIterator(
          OcrBlock.valueOf(params.get(OcrHighlightParams.CONTEXT_BLOCK, "line").toUpperCase()),
          OcrBlock.valueOf(params.get(OcrHighlightParams.LIMIT_BLOCK, "block").toUpperCase()),
          params.getInt(OcrHighlightParams.CONTEXT_SIZE, 2));
      OcrPassageFormatter ocrFormatter = ocrFormat.getPassageFormatter(
          params.get(HighlightParams.TAG_PRE, "<em>"),
          params.get(HighlightParams.TAG_POST, "</em>"),
          params.getBool(OcrHighlightParams.ABSOLUTE_HIGHLIGHTS, false));
      ocrSnippets = ocrHighlighter.highlightOcrFields(
          ocrFieldNames, query, docIDs, maxPassagesOcr, ocrBreakIterator, ocrFormatter,
          params.get(OcrHighlightParams.PAGE_ID, null));
    }

    // Assemble output data
    SimpleOrderedMap out = new SimpleOrderedMap();
    if (ocrSnippets != null) {
      this.addOcrSnippets(out, keys, ocrFieldNames, ocrSnippets);
    }
    if (!highlightFieldWarnings.isEmpty()) {
      SimpleOrderedMap<String> hlWarnings = new SimpleOrderedMap<>();
      highlightFieldWarnings.forEach(hlWarnings::add);
      out.add("warnings", hlWarnings);
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
                              OcrHighlightResult[] ocrSnippets) {
    for (int k=0; k < keys.length; k++) {
      String docId = keys[k];
      SimpleOrderedMap docMap = (SimpleOrderedMap) out.get(docId);
      if (docMap == null) {
        docMap = new SimpleOrderedMap();
        out.add(docId, docMap);
      }
      if (ocrSnippets[k] == null) {
        continue;
      }
      docMap.addAll(ocrSnippets[k].toNamedList());
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
