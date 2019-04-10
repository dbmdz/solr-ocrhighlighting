package org.mdz.search.solrocr.lucene.byteoffset;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.apache.lucene.index.FieldInfos;
import org.apache.lucene.index.FilterLeafReader;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.NumericDocValues;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.Scorer;
import org.apache.lucene.search.TwoPhaseIterator;
import org.apache.lucene.search.Weight;
import org.apache.lucene.search.spans.SpanCollector;
import org.apache.lucene.search.spans.SpanQuery;
import org.apache.lucene.search.spans.SpanScorer;
import org.apache.lucene.search.spans.Spans;
import org.apache.lucene.search.uhighlight.PhraseHelper;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.PriorityQueue;

/**
 * Customization of {@link PhraseHelper} to add support for byte offsets from payloads
 *
 * About 80% of this code is copied straight from the original class.
 */
public class ByteOffsetPhraseHelper extends PhraseHelper {

  public static final ByteOffsetPhraseHelper NONE = new ByteOffsetPhraseHelper(
      new MatchAllDocsQuery(), "_ignored_",
      (s) -> false, spanQuery -> null, query -> null, true);

  private final Predicate<String> fieldMatcher;
  private final String fieldName;
  private final Set<BytesRef> positionInsensitiveTerms; // (TermQuery terms)
  private Method _createWeight;

  public ByteOffsetPhraseHelper(Query query, String field,
      Predicate<String> fieldMatcher,
      Function<SpanQuery, Boolean> rewriteQueryPred,
      Function<Query, Collection<Query>> preExtractRewriteFunction,
      boolean ignoreQueriesNeedingRewrite) {
    super(query, field, fieldMatcher, rewriteQueryPred, preExtractRewriteFunction, ignoreQueriesNeedingRewrite);

    this.fieldMatcher = fieldMatcher;
    this.fieldName = field;
    positionInsensitiveTerms = Arrays.stream(this.getAllPositionInsensitiveTerms()).collect(Collectors.toSet());

  }

  private Weight createWeight(IndexSearcher searcher, Query query) throws IOException {
    // NOTE: We have to use reflection for this, since the createWeight API has changed between 7.x and 8.x
    if (this._createWeight == null) {
      this._createWeight = Arrays.stream(searcher.getClass().getDeclaredMethods())
          .filter(m -> m.getName().equals("createWeight"))
          .findFirst().orElseThrow(() -> new RuntimeException("Incompatible Lucene/Solr version, needs 7.x or 8.x."));
    }
    try {
      if (_createWeight.getParameterTypes()[1] == boolean.class) {
        return (Weight) _createWeight.invoke(
            searcher, searcher.rewrite(query), false /* no scores */, 1f /* boost */);
      } else {
        return (Weight) _createWeight.invoke(
            searcher, searcher.rewrite(query), ScoreMode.COMPLETE_NO_SCORES, 1f /* boost */);
      }
    } catch (IllegalAccessException|InvocationTargetException|IllegalArgumentException e) {
      throw new RuntimeException("Incompatible Lucene/Solr version, needs 7.x or 8.x.");
    }
  }

  public void createByteOffsetsEnumsForSpans(LeafReader leafReader, int docId, List<ByteOffsetsEnum> results) throws IOException {
    leafReader = new SingleFieldWithPayloadsFilterLeafReader(leafReader, this.fieldName);
    //TODO avoid searcher and do what it does to rewrite & get weight?
    IndexSearcher searcher = new IndexSearcher(leafReader);
    searcher.setQueryCache(null);

    // for each SpanQuery, grab it's Spans and put it into a PriorityQueue
    PriorityQueue<Spans> spansPriorityQueue = new PriorityQueue<Spans>(getSpanQueries().size()) {
      @Override
      protected boolean lessThan(Spans a, Spans b) {
        return a.startPosition() <= b.startPosition();
      }
    };
    for (Query query : getSpanQueries()) {
      Weight weight = createWeight(searcher, query);
      Scorer scorer = weight.scorer(leafReader.getContext());
      if (scorer == null) {
        continue;
      }
      TwoPhaseIterator twoPhaseIterator = scorer.twoPhaseIterator();
      if (twoPhaseIterator != null) {
        if (twoPhaseIterator.approximation().advance(docId) != docId || !twoPhaseIterator.matches()) {
          continue;
        }
      } else if (scorer.iterator().advance(docId) != docId) { // preposition, and return doing nothing if find none
        continue;
      }

      Spans spans = ((SpanScorer) scorer).getSpans();
      assert spans.docID() == docId;
      if (spans.nextStartPosition() != Spans.NO_MORE_POSITIONS) {
        spansPriorityQueue.add(spans);
      }
    }

    // Iterate the Spans in the PriorityQueue, collecting as we go.  By using a PriorityQueue ordered by position,
    //   the underlying offsets in our collector will be mostly appended to the end of arrays (efficient).
    // note: alternatively it'd interesting if we produced one OffsetsEnum that internally advanced
    //   this PriorityQueue when nextPosition is called; it would cap what we have to cache for large docs and
    //   exiting early (due to maxLen) is easy.
    //   But at least we have an accurate "freq" and it shouldn't be too much data to collect.  Even SpanScorer
    //   navigates the spans fully to compute a good freq (and thus score)!
    ByteOffsetSpanCollector spanCollector = new ByteOffsetSpanCollector();
    while (spansPriorityQueue.size() > 0) {
      Spans spans = spansPriorityQueue.top();
      //TODO limit to a capped endOffset length somehow so we can break this loop early
      spans.collect(spanCollector);

      if (spans.nextStartPosition() == Spans.NO_MORE_POSITIONS) {
        spansPriorityQueue.pop();
      } else {
        spansPriorityQueue.updateTop();
      }
    }
    results.addAll(spanCollector.termToByteOffsetsEnums.values());
  }

  private static final class SingleFieldWithPayloadsFilterLeafReader extends FilterLeafReader {
    final String fieldName;

    SingleFieldWithPayloadsFilterLeafReader(LeafReader in, String fieldName) {
      super(in);
      this.fieldName = fieldName;
    }

    @Override
    public FieldInfos getFieldInfos() {
      throw new UnsupportedOperationException();//TODO merge them
    }

    @Override
    public Terms terms(String field) throws IOException {
      // ensure the underlying PostingsEnum returns offsets.  It's sad we have to do this to use the SpanCollector.
      return new FilterTerms(super.terms(fieldName)) {
        @Override
        public TermsEnum iterator() throws IOException {
          return new FilterTermsEnum(in.iterator()) {
            @Override
            public PostingsEnum postings(PostingsEnum reuse, int flags) throws IOException {
              return super.postings(reuse, flags | PostingsEnum.PAYLOADS);
            }
          };
        }
      };
    }

    @Override
    public NumericDocValues getNormValues(String field) throws IOException {
      return super.getNormValues(fieldName);
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

  private class ByteOffsetSpanCollector implements SpanCollector {
    Map<BytesRef, SpanCollectedByteOffsetsEnum> termToByteOffsetsEnums = new HashMap<>();

    @Override
    public void collectLeaf(PostingsEnum postings, int position, Term term) throws IOException {
      if (!fieldMatcher.test(term.field())) {
        return;
      }

      SpanCollectedByteOffsetsEnum byteOffsetsEnum = termToByteOffsetsEnums.get(term.bytes());
      if (byteOffsetsEnum == null) {
        // If it's pos insensitive we handle it outside of PhraseHelper.  term.field() is from the Query.
        if (positionInsensitiveTerms.contains(term.bytes())) {
          return;
        }
        byteOffsetsEnum = new SpanCollectedByteOffsetsEnum(term.bytes(), postings.freq());
        termToByteOffsetsEnums.put(term.bytes(), byteOffsetsEnum);
      }
      byteOffsetsEnum.add(ByteOffsetEncoder.decode(postings.getPayload()));
    }

    @Override
    public void reset() { // called when at a new position.  We don't care.
    }
  }

  private static class SpanCollectedByteOffsetsEnum extends ByteOffsetsEnum {
    // TODO perhaps optionally collect (and expose) payloads?
    private final BytesRef term;
    private final int[] offsets;
    private int numPairs = 0;
    private int enumIdx = -1;

    private SpanCollectedByteOffsetsEnum(BytesRef term, int postingsFreq) {
      this.term = term;
      this.offsets = new int[postingsFreq]; // hopefully not wasteful?  At least we needn't resize it.
    }

    // called from collector before it's navigated
    void add(int byteOffset) {
      assert enumIdx == -1 : "bad state";

      // loop backwards since we expect a match at the end or close to it.  We expect O(1) not O(N).
      int pairIdx = numPairs - 1;
      for (; pairIdx >= 0; pairIdx--) {
        int iByteOffset = offsets[pairIdx];
        int cmp = Integer.compare(iByteOffset, byteOffset);
        if (cmp == 0) {
          return; // we already have this offset for this term
        } else if (cmp < 0) {
          break; //we will insert offsetPair to the right of pairIdx
        }
      }
      // pairIdx is now one position to the left of where we insert the new pair
      // shift right any pairs by one to make room
      final int shiftLen = numPairs - (pairIdx + 1);
      if (shiftLen > 0) {
        System.arraycopy(offsets, pairIdx + 1, offsets, pairIdx + 2, shiftLen);
      }
      // now we can place the offset pair
      offsets[pairIdx + 1] = byteOffset;
      numPairs++;
    }

    @Override
    public boolean nextPosition() throws IOException {
      return ++enumIdx < numPairs;
    }

    @Override
    public int freq() throws IOException {
      return numPairs;
    }

    @Override
    public BytesRef getTerm() throws IOException {
      return term;
    }

    @Override
    public int byteOffset() throws IOException {
      return offsets[enumIdx];
    }
  }
}
