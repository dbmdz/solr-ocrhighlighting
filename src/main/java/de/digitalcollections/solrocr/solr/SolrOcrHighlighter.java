package de.digitalcollections.solrocr.solr;

import de.digitalcollections.solrocr.lucene.OcrHighlighter;
import de.digitalcollections.solrocr.model.OcrHighlightResult;
import java.io.IOException;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.apache.lucene.search.Query;
import org.apache.solr.common.params.HighlightParams;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.common.util.SimpleOrderedMap;
import org.apache.solr.highlight.UnifiedSolrHighlighter;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.search.DocList;
import org.apache.solr.util.SolrPluginUtils;

public class SolrOcrHighlighter extends UnifiedSolrHighlighter {
  public NamedList<Object> doHighlighting(
      DocList docs, Query query, SolrQueryRequest req, Map<String, Object> respHeader)
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
    String[] ocrFieldNames = getOcrHighlightFields(req);
    // No output if no fields were defined
    if (ocrFieldNames == null || ocrFieldNames.length == 0) {
      return null;
    }
    int[] maxPassagesOcr = getMaxPassages(ocrFieldNames, params);

    // Highlight OCR fields
    OcrHighlighter ocrHighlighter = new OcrHighlighter(
        req.getSearcher(), req.getSchema().getIndexAnalyzer(), req.getParams());
    OcrHighlightResult[] ocrSnippets = ocrHighlighter.highlightOcrFields(
        ocrFieldNames, query, docIDs, maxPassagesOcr, respHeader);

    // Assemble output data
    SimpleOrderedMap<Object> out = new SimpleOrderedMap<>();
    if (ocrSnippets != null) {
      this.addOcrSnippets(out, keys, ocrSnippets);
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

  private void addOcrSnippets(NamedList<Object> out, String[] keys, OcrHighlightResult[] ocrSnippets) {
    for (int k=0; k < keys.length; k++) {
      String docId = keys[k];
      SimpleOrderedMap<Object> docMap = (SimpleOrderedMap<Object>) out.get(docId);
      if (docMap == null) {
        docMap = new SimpleOrderedMap<>();
        out.add(docId, docMap);
      }
      if (ocrSnippets[k] == null) {
        continue;
      }
      docMap.addAll(ocrSnippets[k].toNamedList());
    }
  }

  /** Obtain all fields among the requested fields that contain OCR data. */
  private String[] getOcrHighlightFields(SolrQueryRequest req) {
    String[] fields = req.getParams().getParams(OcrHighlightParams.OCR_FIELDS);

    if (fields != null && fields.length > 0) {
      Set<String> expandedFields = new LinkedHashSet<>();
      Collection<String> storedHighlightFieldNames = req.getSearcher().getDocFetcher().getStoredHighlightFieldNames();
      for (String field : fields) {
        expandWildcardsInHighlightFields(
            expandedFields,
            storedHighlightFieldNames,
            SolrPluginUtils.split(field));
      }
      fields = expandedFields.toArray(new String[]{});
      // Trim them now in case they haven't been yet.  Not needed for all code-paths above but do it here.
      for (int i = 0; i < fields.length; i++) {
        fields[i] = fields[i].trim();
      }
    }
    return fields;
  }

  static private void expandWildcardsInHighlightFields (
      // Copied verbatim from SolrHighlighter
      Set<String> expandedFields,
      Collection<String> storedHighlightFieldNames,
      String... fields) {
    for (String field : fields) {
      if (field.contains("*")) {
        // create a Java regular expression from the wildcard string
        String fieldRegex = field.replace("\\*", ".*");
        for (String storedFieldName : storedHighlightFieldNames) {
          if (storedFieldName.matches(fieldRegex)) {
            expandedFields.add(storedFieldName);
          }
        }
      } else {
        expandedFields.add(field);
      }
    }
  }
}
