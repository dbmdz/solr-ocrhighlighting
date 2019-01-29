package org.mdz.search.solrocr.solr;

import com.google.common.collect.Sets;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.stream.Stream;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.uhighlight.UnifiedHighlighter;
import org.apache.solr.common.params.HighlightParams;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.highlight.UnifiedSolrHighlighter;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.schema.SchemaField;
import org.apache.solr.search.DocList;
import org.mdz.search.solrocr.lucene.OcrHighlighter;
import org.mdz.search.solrocr.lucene.OcrSnippet;

public class SolrOcrHighlighter extends UnifiedSolrHighlighter {

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
    UnifiedHighlighter regularHighlighter = getHighlighter(req);
    Map<String, String[]> regularSnippets = regularHighlighter.highlightFields(regularFieldNames, query, docIDs, maxPassagesRegular);

    // Highlight OCR fields
    OcrHighlighter ocrHighlighter = new OcrHighlighter(req.getSearcher(), req.getSchema().getIndexAnalyzer());
    Map<String, OcrSnippet[]> ocrSnippets = ocrHighlighter.highlightOcrFields(ocrFieldNames, query, docIDs, maxPassagesOcr);

    // Assemble output data
    NamedList<Object> out = this.encodeSnippets(keys, regularFieldNames, regularSnippets);
    this.addOcrSnippets(out, keys, ocrFieldNames, ocrSnippets);

    return out;
  }

  private int[] getMaxPassages(String[] fieldNames, SolrParams params) {
    int[] maxPassages = new int[fieldNames.length];
    for (int i = 0; i < fieldNames.length; i++) {
      maxPassages[i] = params.getFieldInt(fieldNames[i], HighlightParams.SNIPPETS, 1);
    }
    return maxPassages;
  }

  private void addOcrSnippets(NamedList<Object> out, String[] keys, String[] ocrFieldNames, Map<String, OcrSnippet[]> ocrSnippets) {
    // TODO: Implement
  }

  /**
   * Obtain all fields among the requested fields that contain OCR data.
   *
   * By definition, a field contains OCR data if there is a corresponding field that contains its OCR path.
   * Currently these have a hardcoded prefix of `ocrpath.`, but this will later be made configurable
   */
  private String[] getOcrHighlightFields(Query query, SolrQueryRequest req, String[] defaultFields) {
    HashSet<String> highlightFields = Sets.newHashSet(this.getHighlightFields(query, req, defaultFields));
    return req.getSchema().getFields().values().stream()
        .filter(f -> f.getName().startsWith("ocrpath."))
        .map(SchemaField::getName)
        .filter(highlightFields::contains)
        .toArray(String[]::new);
  }
}
