package org.mdz.search.solrocr.lucene;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.StoredFieldVisitor;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.uhighlight.UnifiedHighlighter;

public class OcrHighlighter extends UnifiedHighlighter {
  public OcrHighlighter(IndexSearcher indexSearcher,
                        Analyzer indexAnalyzer) {
    super(indexSearcher, indexAnalyzer);
  }

  public Map<String, OcrSnippet[]> highlightOcrFields(String[] ocrFieldNames, Query query, int[] docIDs, int[] maxPassagesOcr) {
    // TODO: Implement (labs/solr-ocr-plugin#4)
    return new HashMap<>();
  }

  protected List<CharSequence[]> loadOcrFieldValues(String[] fields, DocIdSetIterator docIter) throws IOException {
    // TODO: Read this prefix from the configuration and only use `ocrpath` as the default
    String ocrPathFieldPrefix = "ocrpath";
    List<CharSequence[]> fieldValues = new ArrayList<>((int) docIter.cost());
    OcrPathStoredFieldVisitor visitor = new OcrPathStoredFieldVisitor(ocrPathFieldPrefix);
    int docId;
    while ((docId = docIter.nextDoc()) != DocIdSetIterator.NO_MORE_DOCS) {
      CharSequence[] ocrVals = new CharSequence[fields.length];
      searcher.doc(docId, visitor);
      HashMap<String, String> paths = visitor.getOcrPaths();
      for (Entry<String, String> entry : paths.entrySet()) {
        int fieldIdx = Arrays.binarySearch(fields, entry.getKey());
        if (fieldIdx < 0) {
          continue;
        }
        ocrVals[fieldIdx] = this.loadOcrText(entry.getValue());
      }
      fieldValues.add(ocrVals);
      visitor.reset();
    }
    return fieldValues;
  }

  protected String loadOcrText(String ocrPath) throws IOException {
    return new String(Files.readAllBytes(Paths.get(ocrPath)), StandardCharsets.UTF_8);
  }

  @Override
  protected BreakIterator getBreakIterator(String field) {
    return new OcrBreakIterator();
  }

  /**
   * Visits stored field values and for every field with a name that has the configured prefix, stores the value
   * in the values map.
   */
  protected class OcrPathStoredFieldVisitor extends StoredFieldVisitor {

    private final String ocrPathFieldPrefix;
    private HashMap<String, String> values;

    public OcrPathStoredFieldVisitor(String ocrPathFieldPrefix) {
      this.ocrPathFieldPrefix = ocrPathFieldPrefix;
      this.values = new HashMap<>();
    }

    public void reset() {
      this.values = new HashMap<>();
    }

    public HashMap<String, String> getOcrPaths() {
      HashMap<String, String> paths = new HashMap<>();
      // Strip the field prefix from the field name
      for (Entry<String, String> entry : this.values.entrySet()) {
        paths.put(entry.getKey().replaceFirst(ocrPathFieldPrefix + ".", ""),
                  entry.getValue());
      }
      return paths;
    }

    @Override
    public void stringField(FieldInfo fieldInfo, byte[] byteValue) throws IOException {
      String value = new String(byteValue, StandardCharsets.UTF_8);
      this.values.put(fieldInfo.name, value);
    }

    @Override
    public Status needsField(FieldInfo fieldInfo) throws IOException {
      return fieldInfo.name.startsWith(this.ocrPathFieldPrefix) ? Status.YES : Status.NO;
    }
  }
}
