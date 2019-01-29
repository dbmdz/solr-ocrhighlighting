package org.mdz.search.solrocr.solr;

import java.io.IOException;
import java.util.Map;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.uhighlight.UnifiedHighlighter;
import org.apache.solr.common.params.HighlightParams;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.highlight.UnifiedSolrHighlighter;
import org.apache.solr.request.SolrQueryRequest;
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
    String[] regularFieldNames = getHighlightFields(query, req, defaultFields);
    String[] ocrFieldNames = getOcrHighlightFields(query, req, defaultFields);

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

  private String[] getOcrHighlightFields(Query query, SolrQueryRequest req, String[] defaultFields) {
    // TODO: Determine which fields contain OCR data
    // TODO: Determine which of these fields need to be highlighted
    return new String[0];
  }
}
