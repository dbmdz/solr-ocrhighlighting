package org.mdz.search.solrocr.lucene;

import java.io.IOException;
import java.text.BreakIterator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.lucene.analysis.Analyzer;
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

  @Override
  protected List<CharSequence[]> loadFieldValues(String[] fields, DocIdSetIterator docIter, int cacheCharsThreshold)
      throws IOException {
    // TODO: Implement (labs/solr-ocr-plugin#3)
    return super.loadFieldValues(fields, docIter, cacheCharsThreshold);
  }

  @Override
  protected BreakIterator getBreakIterator(String field) {
    return new OcrBreakIterator();
  }
}
