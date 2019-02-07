package org.mdz.search.solrocr.lucene;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.function.Predicate;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.index.BaseCompositeReader;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.Fields;
import org.apache.lucene.index.FilterLeafReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.ReaderUtil;
import org.apache.lucene.index.StoredFieldVisitor;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.uhighlight.PhraseHelper;
import org.apache.lucene.search.uhighlight.UHComponents;
import org.apache.lucene.search.uhighlight.UnifiedHighlighter;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.InPlaceMergeSorter;
import org.apache.lucene.util.automaton.CharacterRunAutomaton;
import org.mdz.search.solrocr.util.FileCharIterator;

public class OcrHighlighter extends UnifiedHighlighter {
  public OcrHighlighter(IndexSearcher indexSearcher,
                        Analyzer indexAnalyzer) {
    super(indexSearcher, indexAnalyzer);
  }

  // FIXME: This is largely (>80%) copied straight from
  //        {@link UnifiedHighlighter#highlightFieldsAsObjects(String[], Query, int[], int[])}
  //        Would be nice if the architecture allowed for easier overriding of some things...
  public Map<String, OcrSnippet[][]> highlightOcrFields(
      String[] ocrFieldNames, Query query, int[] docIDs, int[] maxPassagesOcr, String breakTag,
      int contextSize) throws IOException {
    if (ocrFieldNames.length < 1) {
      throw new IllegalArgumentException("ocrFieldNames must not be empty");
    }
    if (ocrFieldNames.length != maxPassagesOcr.length) {
      throw new IllegalArgumentException("invalid number of maxPassagesOcr");
    }
    if (searcher == null) {
      throw new IllegalStateException("This method requires that an indexSearcher was passed in the "
                                    + "constructor.  Perhaps you mean to call highlightWithoutSearcher?");
    }

    // Sort docs & fields for sequential i/o
    // Sort doc IDs w/ index to original order: (copy input arrays since we sort in-place)
    int[] docIds = new int[docIDs.length];
    int[] docInIndexes = new int[docIds.length]; // fill in ascending order; points into docIdsIn[]
    copyAndSortDocIdsWithIndex(docIDs, docIds, docInIndexes); // latter 2 are "out" params

    // Sort fields w/ maxPassages pair: (copy input arrays since we sort in-place)
    final String[] fields = new String[ocrFieldNames.length];
    final int[] maxPassages = new int[maxPassagesOcr.length];
    copyAndSortFieldsWithMaxPassages(ocrFieldNames, maxPassagesOcr, fields, maxPassages); // latter 2 are "out" params

    // Init field highlighters (where most of the highlight logic lives, and on a per field basis)
    Set<Term> queryTerms = extractTerms(query);
    OcrFieldHighlighter[] fieldHighlighters = new OcrFieldHighlighter[fields.length];
    int numTermVectors = 0;
    int numPostings = 0;
    for (int f = 0; f < fields.length; f++) {
      OcrFieldHighlighter fieldHighlighter = getOcrFieldHighlighter(
          fields[f], query, queryTerms, maxPassages[f], breakTag, contextSize);
      fieldHighlighters[f] = fieldHighlighter;

      switch (fieldHighlighter.getOffsetSource()) {
        case TERM_VECTORS:
          numTermVectors++;
          break;
        case POSTINGS:
          numPostings++;
          break;
        case POSTINGS_WITH_TERM_VECTORS:
          numTermVectors++;
          numPostings++;
          break;
        case ANALYSIS:
        case NONE_NEEDED:
        default:
          //do nothing
          // FIXME: This will raise a RuntimeException down the road, catch early?
          break;
      }
    }

    IndexReader indexReaderWithTermVecCache =
        (numTermVectors >= 2) ? TermVectorReusingLeafReader.wrap(searcher.getIndexReader()) : null;

    // [fieldIdx][docIdInIndex] of highlightDoc result
    OcrSnippet[][][] highlightDocsInByField = new OcrSnippet[fields.length][docIds.length][];
    // Highlight in doc batches determined by loadFieldValues (consumes from docIdIter)
    DocIdSetIterator docIdIter = asDocIdSetIterator(docIds);
    for (int batchDocIdx = 0; batchDocIdx < docIds.length; ) {
      // Load the field values of the first batch of document(s) (note: commonly all docs are in this batch)
      List<FileCharIterator[]> fieldValsByDoc = loadOcrFieldValues(fields, docIdIter);
      //List<CharSequence[]> fieldValsByDoc = loadFieldValues(fields, docIdIter, -1);
      //    the size of the above list is the size of the batch (num of docs in the batch)

      // Highlight in per-field order first, then by doc (better I/O pattern)
      for (int fieldIdx = 0; fieldIdx < fields.length; fieldIdx++) {
        OcrSnippet[][] resultByDocIn = highlightDocsInByField[fieldIdx];//parallel to docIdsIn
        OcrFieldHighlighter fieldHighlighter = fieldHighlighters[fieldIdx];
        for (int docIdx = batchDocIdx; docIdx - batchDocIdx < fieldValsByDoc.size(); docIdx++) {
          int docId = docIds[docIdx];//sorted order
          FileCharIterator content = fieldValsByDoc.get(docIdx - batchDocIdx)[fieldIdx];
          //CharSequence content = fieldValsByDoc.get(docIdx - batchDocIdx)[fieldIdx];
          if (content == null) {
            continue;
          }
          IndexReader indexReader =
              (fieldHighlighter.getOffsetSource() == OffsetSource.TERM_VECTORS
                  && indexReaderWithTermVecCache != null)
                  ? indexReaderWithTermVecCache
                  : searcher.getIndexReader();
          final LeafReader leafReader;
          if (indexReader instanceof LeafReader) {
            leafReader = (LeafReader) indexReader;
          } else {
            List<LeafReaderContext> leaves = indexReader.leaves();
            LeafReaderContext leafReaderContext = leaves.get(ReaderUtil.subIndex(docId, leaves));
            leafReader = leafReaderContext.reader();
            docId -= leafReaderContext.docBase; // adjust 'doc' to be within this leaf reader
          }
          int docInIndex = docInIndexes[docIdx];//original input order
          assert resultByDocIn[docInIndex] == null;
          resultByDocIn[docInIndex] = fieldHighlighter.highlightFieldForDoc(leafReader, docId, content);
        }
      }
      batchDocIdx += fieldValsByDoc.size();
    }
    assert docIdIter.docID() == DocIdSetIterator.NO_MORE_DOCS
        || docIdIter.nextDoc() == DocIdSetIterator.NO_MORE_DOCS;

    // field -> object highlights parallel to docIdsIn
    Map<String, OcrSnippet[][]> resultMap = new HashMap<>(fields.length);
    for (int f = 0; f < fields.length; f++) {
      resultMap.put(fields[f], highlightDocsInByField[f]);
    }
    return resultMap;
  }

  @Override
  protected List<CharSequence[]> loadFieldValues(String[] fields, DocIdSetIterator docIter, int cacheCharsThreshold)
      throws IOException {
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
        ocrVals[fieldIdx] = new String(Files.readAllBytes(Paths.get(entry.getValue())), StandardCharsets.UTF_16);
      }
      fieldValues.add(ocrVals);
      visitor.reset();
    }
    return fieldValues;

  }

  protected List<FileCharIterator[]> loadOcrFieldValues(String[] fields, DocIdSetIterator docIter) throws IOException {
    // TODO: Read this prefix from the configuration and only use `ocrpath` as the default
    String ocrPathFieldPrefix = "ocrpath";
    List<FileCharIterator[]> fieldValues = new ArrayList<>((int) docIter.cost());
    OcrPathStoredFieldVisitor visitor = new OcrPathStoredFieldVisitor(ocrPathFieldPrefix);
    int docId;
    while ((docId = docIter.nextDoc()) != DocIdSetIterator.NO_MORE_DOCS) {
      FileCharIterator[] ocrVals = new FileCharIterator[fields.length];
      searcher.doc(docId, visitor);
      HashMap<String, String> paths = visitor.getOcrPaths();
      for (Entry<String, String> entry : paths.entrySet()) {
        int fieldIdx = Arrays.binarySearch(fields, entry.getKey());
        if (fieldIdx < 0) {
          continue;
        }
        ocrVals[fieldIdx] = new FileCharIterator(Paths.get(entry.getValue()));
      }
      fieldValues.add(ocrVals);
      visitor.reset();
    }
    return fieldValues;
  }

  private OcrFieldHighlighter getOcrFieldHighlighter(
      String field, Query query, Set<Term> allTerms, int maxPassages, String breakTag, int contextSize) {
    Predicate<String> fieldMatcher = getFieldMatcher(field);
    BytesRef[] terms = filterExtractedTerms(fieldMatcher, allTerms);
    Set<HighlightFlag> highlightFlags = getFlags(field);
    PhraseHelper phraseHelper = getPhraseHelper(field, query, highlightFlags);
    CharacterRunAutomaton[] automata = getAutomata(field, query, highlightFlags);
    OffsetSource offsetSource = getOptimizedOffsetSource(field, terms, phraseHelper, automata);
    UHComponents components = new UHComponents(field, fieldMatcher, query, terms, phraseHelper, automata, highlightFlags);
    return new OcrFieldHighlighter(field, getOffsetStrategy(offsetSource, components),
                                   getScorer(field), maxPassages,
                                   getMaxNoHighlightPassages(field), breakTag, contextSize);
  }

  // FIXME: This is copied straight from UnifiedHighlighter because it has private access there. Maybe open an issue to
  //        make it protected?
  private void copyAndSortFieldsWithMaxPassages(String[] fieldsIn, int[] maxPassagesIn, final String[] fields,
                                                final int[] maxPassages) {
    System.arraycopy(fieldsIn, 0, fields, 0, fieldsIn.length);
    System.arraycopy(maxPassagesIn, 0, maxPassages, 0, maxPassagesIn.length);
    new InPlaceMergeSorter() {
      @Override
      protected void swap(int i, int j) {
        String tmp = fields[i];
        fields[i] = fields[j];
        fields[j] = tmp;
        int tmp2 = maxPassages[i];
        maxPassages[i] = maxPassages[j];
        maxPassages[j] = tmp2;
      }

      @Override
      protected int compare(int i, int j) {
        return fields[i].compareTo(fields[j]);
      }

    }.sort(0, fields.length);
  }

  // FIXME: This is copied straight from UnifiedHighlighter because it has private access there. Maybe open an issue to
  //        make it protected?
  private void copyAndSortDocIdsWithIndex(int[] docIdsIn, final int[] docIds, final int[] docInIndexes) {
    System.arraycopy(docIdsIn, 0, docIds, 0, docIdsIn.length);
    for (int i = 0; i < docInIndexes.length; i++) {
      docInIndexes[i] = i;
    }
    new InPlaceMergeSorter() {
      @Override
      protected void swap(int i, int j) {
        int tmp = docIds[i];
        docIds[i] = docIds[j];
        docIds[j] = tmp;
        tmp = docInIndexes[i];
        docInIndexes[i] = docInIndexes[j];
        docInIndexes[j] = tmp;
      }

      @Override
      protected int compare(int i, int j) {
        return Integer.compare(docIds[i], docIds[j]);
      }
    }.sort(0, docIds.length);
  }

  // FIXME: And another one copied straight from UnifiedHighlighter because it has private access.
  private DocIdSetIterator asDocIdSetIterator(int[] sortedDocIds) {
    return new DocIdSetIterator() {
      int idx = -1;

      @Override
      public int docID() {
        if (idx < 0 || idx >= sortedDocIds.length) {
          return NO_MORE_DOCS;
        }
        return sortedDocIds[idx];
      }

      @Override
      public int nextDoc() throws IOException {
        idx++;
        return docID();
      }

      @Override
      public int advance(int target) throws IOException {
        return super.slowAdvance(target); // won't be called, so whatever
      }

      @Override
      public long cost() {
        return Math.max(0, sortedDocIds.length - (idx + 1)); // remaining docs
      }
    };
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

  /**
   * Wraps an IndexReader that remembers/caches the last call to {@link LeafReader#getTermVectors(int)} so that
   * if the next call has the same ID, then it is reused.  If TV's were column-stride (like doc-values), there would
   * be no need for this.
   */
  // FIXME: This is copied straight from UnifiedHighlighter because it has private access...
  private static class TermVectorReusingLeafReader extends FilterLeafReader {
    static IndexReader wrap(IndexReader reader) throws IOException {
      LeafReader[] leafReaders = reader.leaves().stream()
          .map(LeafReaderContext::reader)
          .map(TermVectorReusingLeafReader::new)
          .toArray(LeafReader[]::new);
      return new BaseCompositeReader<IndexReader>(leafReaders) {
        @Override
        protected void doClose() throws IOException {
          reader.close();
        }

        @Override
        public CacheHelper getReaderCacheHelper() {
          return null;
        }
      };
    }

    private int lastDocId = -1;
    private Fields tvFields;

    TermVectorReusingLeafReader(LeafReader in) {
      super(in);
    }

    @Override
    public Fields getTermVectors(int docID) throws IOException {
      if (docID != lastDocId) {
        lastDocId = docID;
        tvFields = in.getTermVectors(docID);
      }
      return tvFields;
    }

    @Override
    public CacheHelper getCoreCacheHelper() {
      return null;
    }

    @Override
    public CacheHelper getReaderCacheHelper() {
      return null;
    }
  }
}
