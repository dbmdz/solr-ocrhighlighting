package org.mdz.search.solrocr.lucene;

import java.io.IOException;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.document.DocumentStoredFieldVisitor;
import org.apache.lucene.index.BaseCompositeReader;
import org.apache.lucene.index.Fields;
import org.apache.lucene.index.FilterLeafReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.MultiReader;
import org.apache.lucene.index.ReaderUtil;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.uhighlight.PassageScorer;
import org.apache.lucene.search.uhighlight.PhraseHelper;
import org.apache.lucene.search.uhighlight.UnifiedHighlighter;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.InPlaceMergeSorter;
import org.apache.lucene.util.automaton.CharacterRunAutomaton;
import org.apache.solr.common.params.HighlightParams;
import org.apache.solr.common.params.SolrParams;
import org.mdz.search.solrocr.formats.OcrPassageFormatter;
import org.mdz.search.solrocr.formats.OcrSnippet;
import org.mdz.search.solrocr.lucene.byteoffset.ByteOffsetPhraseHelper;
import org.mdz.search.solrocr.lucene.byteoffset.FieldByteOffsetStrategy;
import org.mdz.search.solrocr.lucene.byteoffset.FieldByteOffsetStrategy.PostingsByteOffsetStrategy;
import org.mdz.search.solrocr.lucene.byteoffset.FieldByteOffsetStrategy.PostingsWithTermVectorsByteOffsetStrategy;
import org.mdz.search.solrocr.lucene.byteoffset.FieldByteOffsetStrategy.TermVectorByteOffsetStrategy;
import org.mdz.search.solrocr.lucene.byteoffset.NoOpByteOffsetStrategy;
import org.mdz.search.solrocr.lucene.fieldloader.ExternalFieldLoader;
import org.mdz.search.solrocr.solr.OcrHighlightParams;
import org.mdz.search.solrocr.util.IterableCharSequence;
import org.mdz.search.solrocr.util.OcrHighlightResult;

/**
 * A {@link UnifiedHighlighter} variant to support lazy-loading field values from arbitrary storage and using byte
 * offsets from term payloads for highlighting instead of character offsets.
 */
public class OcrHighlighter extends UnifiedHighlighter {

  static final IndexSearcher EMPTY_INDEXSEARCHER;

  static {
    try {
      IndexReader emptyReader = new MultiReader();
      EMPTY_INDEXSEARCHER = new IndexSearcher(emptyReader);
      EMPTY_INDEXSEARCHER.setQueryCache(null);
    } catch (IOException bogus) {
      throw new RuntimeException(bogus);
    }
  }

  private final ExternalFieldLoader fieldLoader;
  private final SolrParams params;


  public OcrHighlighter(IndexSearcher indexSearcher, Analyzer indexAnalyzer, ExternalFieldLoader fieldLoader,
      SolrParams params) {
    super(indexSearcher, indexAnalyzer);
    this.fieldLoader = fieldLoader;
    this.params = params;
  }

  @Override
  protected PassageScorer getScorer(String fieldName) {
    float k1 = params.getFieldFloat(fieldName, HighlightParams.SCORE_K1, 1.2f);
    float b = params.getFieldFloat(fieldName, HighlightParams.SCORE_B, 0.75f);
    float pivot = params.getFieldFloat(fieldName, HighlightParams.SCORE_PIVOT, 87f);
    boolean boostEarly = params.getFieldBool(fieldName, OcrHighlightParams.SCORE_BOOST_EARLY, false);
    return new OcrPassageScorer(k1, b, pivot, boostEarly);
  }

  @Override
  public Set<HighlightFlag> getFlags(String field) {
    Set<HighlightFlag> flags = EnumSet.noneOf(HighlightFlag.class);
    if (params.getFieldBool(field, HighlightParams.HIGHLIGHT_MULTI_TERM, true)) {
      flags.add(HighlightFlag.MULTI_TERM_QUERY);
    }
    if (params.getFieldBool(field, HighlightParams.USE_PHRASE_HIGHLIGHTER, true)) {
      flags.add(HighlightFlag.PHRASES);
    }
    flags.add(HighlightFlag.PASSAGE_RELEVANCY_OVER_SPEED);

    if (params.getFieldBool(field, HighlightParams.WEIGHT_MATCHES, false) // true in 8.0
        && flags.contains(HighlightFlag.PHRASES) && flags.contains(HighlightFlag.MULTI_TERM_QUERY)) {
      flags.add(HighlightFlag.WEIGHT_MATCHES);
    }
    return flags;
  }

  public OcrHighlightResult[] highlightOcrFields(
      String[] ocrFieldNames, Query query, int[] docIDs, int[] maxPassagesOcr, BreakIterator breakIter,
      OcrPassageFormatter formatter, String pageId) throws IOException {
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
          fields[f], query, queryTerms, maxPassages[f], breakIter, formatter);
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
    int[][] snippetCountsByField = new int[fields.length][docIds.length];
    // Highlight in doc batches determined by loadFieldValues (consumes from docIdIter)
    DocIdSetIterator docIdIter = asDocIdSetIterator(docIds);
    for (int batchDocIdx = 0; batchDocIdx < docIds.length; ) {
      List<IterableCharSequence[]> fieldValsByDoc = loadOcrFieldValues(fields, docIdIter);

      // Highlight in per-field order first, then by doc (better I/O pattern)
      for (int fieldIdx = 0; fieldIdx < fields.length; fieldIdx++) {
        OcrSnippet[][] resultByDocIn = highlightDocsInByField[fieldIdx];//parallel to docIdsIn
        OcrFieldHighlighter fieldHighlighter = fieldHighlighters[fieldIdx];
        for (int docIdx = batchDocIdx; docIdx - batchDocIdx < fieldValsByDoc.size(); docIdx++) {
          int docId = docIds[docIdx];//sorted order
          IterableCharSequence content = fieldValsByDoc.get(docIdx - batchDocIdx)[fieldIdx];
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
          resultByDocIn[docInIndex] = fieldHighlighter.highlightFieldForDoc(leafReader, docId, content, pageId);
          snippetCountsByField[fieldIdx][docInIndex] = fieldHighlighter.getNumMatches(docId);
        }
      }
      batchDocIdx += fieldValsByDoc.size();
    }
    assert docIdIter.docID() == DocIdSetIterator.NO_MORE_DOCS
        || docIdIter.nextDoc() == DocIdSetIterator.NO_MORE_DOCS;

    OcrHighlightResult[] out = new OcrHighlightResult[docIds.length];
    for (int d=0; d < docIds.length; d++) {
      OcrHighlightResult hl = new OcrHighlightResult();
      for (int f = 0; f < fields.length; f++) {
        if (snippetCountsByField[f][d] <= 0) {
          continue;
        }
        hl.addSnippetsForField(fields[f], highlightDocsInByField[f][d]);
        hl.addSnippetCountForField(fields[f], snippetCountsByField[f][d]);
      }
      if (Arrays.stream(fields).allMatch(f -> hl.getFieldSnippets(f) == null)) {
        continue;
      }
      out[d] = hl;
    }
    return out;
  }

  @Override
  protected List<CharSequence[]> loadFieldValues(String[] fields, DocIdSetIterator docIter, int cacheCharsThreshold)
      throws IOException {
    return loadOcrFieldValues(fields, docIter).stream()
        .map(seqs -> Arrays.stream(seqs).map(IterableCharSequence::toString).toArray(CharSequence[]::new))
        .collect(Collectors.toList());
  }

  protected List<IterableCharSequence[]> loadOcrFieldValues(String[] fields, DocIdSetIterator docIter) throws IOException {
    List<IterableCharSequence[]> fieldValues = new ArrayList<>((int) docIter.cost());
    List<String> storedFields = Arrays.stream(fields)
        .filter(f -> fieldLoader == null || !fieldLoader.isExternalField(f))
        .collect(Collectors.toList());
    if (fieldLoader != null) {
      storedFields.addAll(fieldLoader.getRequiredFields());
    }

    String[] visitorArgs = storedFields.stream().toArray(String[]::new);
    int docId;
    while ((docId = docIter.nextDoc()) != DocIdSetIterator.NO_MORE_DOCS) {
      DocumentStoredFieldVisitor docIdVisitor = new DocumentStoredFieldVisitor(visitorArgs);
      IterableCharSequence[] ocrVals = new IterableCharSequence[fields.length];
      searcher.doc(docId, docIdVisitor);
      for (int fieldIdx=0; fieldIdx < fields.length; fieldIdx++) {
        String fieldName = fields[fieldIdx];
        if (fieldLoader == null || !fieldLoader.isExternalField(fieldName)) {
          ocrVals[fieldIdx] = IterableCharSequence.fromString(docIdVisitor.getDocument().get(fieldName));
        } else {
          Map<String, String> fvals = docIdVisitor.getDocument().getFields().stream()
              .filter(f -> f.stringValue() != null)
              .collect(Collectors.toMap(IndexableField::name, IndexableField::stringValue));
          ocrVals[fieldIdx] = fieldLoader.loadField(fvals, fieldName);
        }
      }
      fieldValues.add(ocrVals);
    }
    return fieldValues;
  }

  private OcrFieldHighlighter getOcrFieldHighlighter(
      String field, Query query, Set<Term> allTerms, int maxPassages, BreakIterator breakIter,
      OcrPassageFormatter formatter) {
    Predicate<String> fieldMatcher = getFieldMatcher(field);
    BytesRef[] terms = filterExtractedTerms(fieldMatcher, allTerms);
    Set<HighlightFlag> highlightFlags = getFlags(field);
    PhraseHelper phraseHelper = getPhraseHelper(field, query, highlightFlags);
    ByteOffsetPhraseHelper byteOffsetPhraseHelper = getByteOffsetPhraseHelper(
        field, query, highlightFlags);
    CharacterRunAutomaton[] automata = getAutomata(field, query, highlightFlags);
    OffsetSource offsetSource = getOptimizedOffsetSource(field, terms, phraseHelper, automata);
    OcrHComponents components = new OcrHComponents(field, fieldMatcher, query, terms, phraseHelper,
                                                   byteOffsetPhraseHelper, automata, highlightFlags);
    return new OcrFieldHighlighter(
        field, getOffsetStrategy(offsetSource, components), getByteOffsetStrategy(offsetSource, components),
        getScorer(field), breakIter, formatter, maxPassages, getMaxNoHighlightPassages(field));
  }

  protected ByteOffsetPhraseHelper getByteOffsetPhraseHelper(
      String field, Query query, Set<HighlightFlag> highlightFlags) {
    boolean useWeightMatchesIter = highlightFlags.contains(HighlightFlag.WEIGHT_MATCHES);
    if (useWeightMatchesIter) {
      return ByteOffsetPhraseHelper.NONE; // will be handled by Weight.matches which always considers phrases
    }
    boolean highlightPhrasesStrictly = highlightFlags.contains(HighlightFlag.PHRASES);
    boolean handleMultiTermQuery = highlightFlags.contains(HighlightFlag.MULTI_TERM_QUERY);
    return highlightPhrasesStrictly ?
           new ByteOffsetPhraseHelper(query, field, getFieldMatcher(field),
                            this::requiresRewrite,
                            this::preSpanQueryRewrite,
                            !handleMultiTermQuery)
           : ByteOffsetPhraseHelper.NONE;
  }
  protected FieldByteOffsetStrategy getByteOffsetStrategy(OffsetSource offsetSource, OcrHComponents components) {
    switch (offsetSource) {
      case NONE_NEEDED:
        return NoOpByteOffsetStrategy.INSTANCE;
      case TERM_VECTORS:
        return new TermVectorByteOffsetStrategy(components);
      case POSTINGS:
        return new PostingsByteOffsetStrategy(components);
      case POSTINGS_WITH_TERM_VECTORS:
        return new PostingsWithTermVectorsByteOffsetStrategy(components);
      default:
        throw new IllegalArgumentException("Unrecognized offset source " + offsetSource);
    }
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
