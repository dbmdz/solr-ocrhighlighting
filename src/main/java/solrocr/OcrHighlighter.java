/*
 * Contains verbatim code and custom code based on code from the Lucene
 * project, licensed under the following terms. All parts where this is
 * the case are clearly marked as such in a source code comment referring
 * to this header.
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE.upstream file distributed
 * with this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * For all parts where this is not the case, refer to the LICENSE file in the
 * repository root.
 */
package solrocr;

import com.github.dbmdz.solrocr.formats.alto.AltoFormat;
import com.github.dbmdz.solrocr.formats.hocr.HocrFormat;
import com.github.dbmdz.solrocr.formats.miniocr.MiniOcrFormat;
import com.github.dbmdz.solrocr.iter.BreakLocator;
import com.github.dbmdz.solrocr.iter.ContextBreakLocator;
import com.github.dbmdz.solrocr.iter.ExitingIterCharSeq;
import com.github.dbmdz.solrocr.iter.FileBytesCharIterator;
import com.github.dbmdz.solrocr.iter.IterableCharSequence;
import com.github.dbmdz.solrocr.iter.MultiFileBytesCharIterator;
import com.github.dbmdz.solrocr.lucene.OcrFieldHighlighter;
import com.github.dbmdz.solrocr.lucene.OcrPassageFormatter;
import com.github.dbmdz.solrocr.lucene.OcrPassageScorer;
import com.github.dbmdz.solrocr.model.OcrBlock;
import com.github.dbmdz.solrocr.model.OcrFormat;
import com.github.dbmdz.solrocr.model.OcrHighlightResult;
import com.github.dbmdz.solrocr.model.OcrSnippet;
import com.github.dbmdz.solrocr.model.SourcePointer;
import com.github.dbmdz.solrocr.reader.LegacyBaseCompositeReader;
import com.github.dbmdz.solrocr.solr.OcrHighlightParams;
import com.github.dbmdz.solrocr.util.TimeAllowedLimit;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.document.DocumentStoredFieldVisitor;
import org.apache.lucene.index.BaseCompositeReader;
import org.apache.lucene.index.ExitableDirectoryReader;
import org.apache.lucene.index.Fields;
import org.apache.lucene.index.FilterLeafReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.MultiReader;
import org.apache.lucene.index.QueryTimeout;
import org.apache.lucene.index.ReaderUtil;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.uhighlight.LabelledCharArrayMatcher;
import org.apache.lucene.search.uhighlight.PassageScorer;
import org.apache.lucene.search.uhighlight.PhraseHelper;
import org.apache.lucene.search.uhighlight.UHComponents;
import org.apache.lucene.search.uhighlight.UnifiedHighlighter;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.InPlaceMergeSorter;
import org.apache.lucene.util.Version;
import org.apache.lucene.util.automaton.CharacterRunAutomaton;
import org.apache.solr.common.params.HighlightParams;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.request.SolrQueryRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link UnifiedHighlighter} variant to support generating snippets with text coordinates from
 * OCR data and lazy-loading field values from external storage.
 */
public class OcrHighlighter extends UnifiedHighlighter {

  private static final Logger log = LoggerFactory.getLogger(OcrHighlighter.class);

  private static final CharacterRunAutomaton[] ZERO_LEN_AUTOMATA_ARRAY_LEGACY =
      new CharacterRunAutomaton[0];
  private static final IndexSearcher EMPTY_INDEXSEARCHER;
  private static final int DEFAULT_SNIPPET_LIMIT = 100;
  public static final String PARTIAL_OCR_HIGHLIGHTS = "partialOcrHighlights";

  private static final boolean VERSION_IS_PRE81 =
      Version.LATEST.major < 8 || (Version.LATEST.major == 8 && Version.LATEST.minor < 1);
  private static final boolean VERSION_IS_PRE82 =
      VERSION_IS_PRE81 || (Version.LATEST.major == 8 && Version.LATEST.minor < 2);
  private static final boolean VERSION_IS_PRE84 =
      VERSION_IS_PRE82 || (Version.LATEST.major == 8 && Version.LATEST.minor < 4);
  private static final boolean VERSION_IS_PRE89 =
      VERSION_IS_PRE82 || (Version.LATEST.major == 8 && Version.LATEST.minor < 9);
  private static final Constructor<UHComponents> hlComponentsConstructorLegacy;
  private static final Method offsetSourceGetterLegacy;
  private static final Method extractAutomataLegacyMethod;

  static {
    /*
     * Copied from the upstream {@link UnifiedHighlighter} code. <strong>Please refer to the file
     * header for licensing information</strong>
     */
    try {
      IndexReader emptyReader = new MultiReader();
      EMPTY_INDEXSEARCHER = new IndexSearcher(emptyReader);
      EMPTY_INDEXSEARCHER.setQueryCache(null);
    } catch (IOException bogus) {
      throw new RuntimeException(bogus);
    }

    // For compatibility with older versions, we grab references to deprecated APIs
    // via reflection and store them as static variables.
    try {
      Method trySetAccessible;
      try {
        trySetAccessible =
            Class.forName("java.lang.reflect.AccessibleObject")
                .getDeclaredMethod("trySetAccessible");
      } catch (NoSuchMethodException e) {
        trySetAccessible = null;
      }

      // Prefer `trySetAccessible` for making methods accessible, which is only available from
      // Java 9 on. For older versions, we still use `setAccessible(true)`. Since the package should
      // be compatible with Java 8, we have to use reflection to call the new API.
      final Method tsa = trySetAccessible;
      final Function<Method, Boolean> makeAccessible =
          (Method m) -> {
            if (tsa != null) {
              try {
                return (boolean) tsa.invoke(m);
              } catch (IllegalAccessException | InvocationTargetException e) {
                return false;
              }
            } else {
              try {
                m.setAccessible(true);
                return true;
              } catch (SecurityException e) {
                return false;
              }
            }
          };

      if (VERSION_IS_PRE81) {
        @SuppressWarnings("rawtypes")
        Class multiTermHl =
            Class.forName("org.apache.lucene.search.uhighlight.MultiTermHighlighting");
        extractAutomataLegacyMethod =
            multiTermHl.getDeclaredMethod(
                "extractAutomata", Query.class, Predicate.class, boolean.class, Function.class);
        if (!makeAccessible.apply(extractAutomataLegacyMethod)) {
          throw new RuntimeException(
              "Could not make `extractAutomata` accessible, are you running a SecurityManager?");
        }
      } else if (VERSION_IS_PRE84) {
        @SuppressWarnings("rawtypes")
        Class multiTermHl =
            Class.forName("org.apache.lucene.search.uhighlight.MultiTermHighlighting");
        extractAutomataLegacyMethod =
            multiTermHl.getDeclaredMethod(
                "extractAutomata", Query.class, Predicate.class, boolean.class);
        if (!makeAccessible.apply(extractAutomataLegacyMethod)) {
          throw new RuntimeException(
              "Could not make `extractAutomata` accessible, are you running a SecurityManager?");
        }
      } else {
        extractAutomataLegacyMethod = null;
      }
      if (VERSION_IS_PRE82) {
        //noinspection JavaReflectionMemberAccess
        hlComponentsConstructorLegacy =
            UHComponents.class.getDeclaredConstructor(
                String.class,
                Predicate.class,
                Query.class,
                BytesRef[].class,
                PhraseHelper.class,
                CharacterRunAutomaton[].class,
                Set.class);
        offsetSourceGetterLegacy =
            UnifiedHighlighter.class.getDeclaredMethod(
                "getOptimizedOffsetSource",
                String.class,
                BytesRef[].class,
                PhraseHelper.class,
                CharacterRunAutomaton[].class);
      } else if (VERSION_IS_PRE84) {
        //noinspection JavaReflectionMemberAccess
        hlComponentsConstructorLegacy =
            UHComponents.class.getDeclaredConstructor(
                String.class,
                Predicate.class,
                Query.class,
                BytesRef[].class,
                PhraseHelper.class,
                CharacterRunAutomaton[].class,
                boolean.class,
                Set.class);
        offsetSourceGetterLegacy = null;
      } else {
        hlComponentsConstructorLegacy = null;
        offsetSourceGetterLegacy = null;
      }
    } catch (NoSuchMethodException | ClassNotFoundException e) {
      throw new RuntimeException(e);
    }
  }

  private final SolrParams params;
  private final SolrQueryRequest req;
  private final Set<OcrFormat> formats;

  public OcrHighlighter(
      IndexSearcher indexSearcher,
      Analyzer indexAnalyzer,
      SolrQueryRequest req,
      Map<String, Map<OcrBlock, Integer>> formatReadSizes) {
    super(indexSearcher, indexAnalyzer);
    this.params = req.getParams();
    this.req = req;
    this.formats = new HashSet<>();
    this.formats.add(new HocrFormat(formatReadSizes.get("hocr")));
    this.formats.add(new AltoFormat(formatReadSizes.get("alto")));
    this.formats.add(new MiniOcrFormat(formatReadSizes.get("miniocr")));
  }

  @Override
  protected PassageScorer getScorer(String fieldName) {
    float k1 = params.getFieldFloat(fieldName, HighlightParams.SCORE_K1, 1.2f);
    float b = params.getFieldFloat(fieldName, HighlightParams.SCORE_B, 0.75f);
    float pivot = params.getFieldFloat(fieldName, HighlightParams.SCORE_PIVOT, 87f);
    boolean boostEarly =
        params.getFieldBool(fieldName, OcrHighlightParams.SCORE_BOOST_EARLY, false);
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
        && flags.contains(HighlightFlag.PHRASES)
        && flags.contains(HighlightFlag.MULTI_TERM_QUERY)) {
      flags.add(HighlightFlag.WEIGHT_MATCHES);
    }
    return flags;
  }

  /**
   * Highlight passages from OCR fields in multiple documents.
   *
   * <p>Heavily based on {@link UnifiedHighlighter#highlightFieldsAsObjects(String[], Query, int[],
   * int[])} with modifications to add support for OCR-specific functionality and timeouts.
   * <strong>Please refer to the file header for licensing information on the original
   * code.</strong>
   */
  public OcrHighlightResult[] highlightOcrFields(
      String[] ocrFieldNames,
      Query query,
      int[] docIDs,
      int[] maxPassagesOcr,
      Map<String, Object> respHeader,
      Executor hlThreadPool)
      throws IOException {
    if (ocrFieldNames.length < 1) {
      throw new IllegalArgumentException("ocrFieldNames must not be empty");
    }
    if (ocrFieldNames.length != maxPassagesOcr.length) {
      throw new IllegalArgumentException("invalid number of maxPassagesOcr");
    }
    if (searcher == null) {
      throw new IllegalStateException(
          "This method requires that an indexSearcher was passed in the "
              + "constructor.  Perhaps you mean to call highlightWithoutSearcher?");
    }
    log.debug(
        "Highlighting OCR fields={} for query={} in docIDs={} with maxPassagesOcr={}",
        ocrFieldNames,
        query,
        docIDs,
        maxPassagesOcr);
    QueryTimeout timeout = null;
    if (TimeAllowedLimit.hasTimeLimit(req)) {
      timeout = new TimeAllowedLimit(req);
    }

    // Sort docs & fields for sequential i/o
    // Sort doc IDs w/ index to original order: (copy input arrays since we sort in-place)
    int[] docIds = new int[docIDs.length];
    int[] docInIndexes = new int[docIds.length]; // fill in ascending order; points into docIdsIn[]
    copyAndSortDocIdsWithIndex(docIDs, docIds, docInIndexes); // latter 2 are "out" params

    // Sort fields w/ maxPassages pair: (copy input arrays since we sort in-place)
    final String[] fields = new String[ocrFieldNames.length];
    final int[] maxPassages = new int[maxPassagesOcr.length];
    copyAndSortFieldsWithMaxPassages(
        ocrFieldNames, maxPassagesOcr, fields, maxPassages); // latter 2 are "out" params

    // Init field highlighters (where most of the highlight logic lives, and on a per field basis)
    Set<Term> queryTerms = extractTerms(query);
    OcrFieldHighlighter[] fieldHighlighters = new OcrFieldHighlighter[fields.length];
    int numTermVectors = 0;
    int numPostings = 0;
    for (int f = 0; f < fields.length; f++) {
      OcrFieldHighlighter fieldHighlighter =
          getOcrFieldHighlighter(fields[f], query, queryTerms, maxPassages[f]);
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
          // do nothing
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

    List<CompletableFuture<Void>> hlFuts = new ArrayList<>();
    docLoop:
    for (int batchDocIdx = 0; batchDocIdx < docIds.length; ) {
      List<IterableCharSequence[]> fieldValsByDoc = loadOcrFieldValues(fields, docIdIter);

      // Highlight in per-field order first, then by doc (better I/O pattern)
      for (int fieldIdx = 0; fieldIdx < fields.length; fieldIdx++) {
        OcrSnippet[][] resultByDocIn = highlightDocsInByField[fieldIdx]; // parallel to docIdsIn
        OcrFieldHighlighter fieldHighlighter = fieldHighlighters[fieldIdx];
        for (int docIdx = batchDocIdx; docIdx - batchDocIdx < fieldValsByDoc.size(); docIdx++) {
          int docId = docIds[docIdx]; // sorted order
          IterableCharSequence content = fieldValsByDoc.get(docIdx - batchDocIdx)[fieldIdx];
          if (content == null) {
            continue;
          }
          if (timeout != null) {
            // We only check against the timeout when reading our field content (both from disk and
            // from memory), since this is a process that is performed at multiple points in the
            // highlighting process and usually takes the longest time.
            content = new ExitingIterCharSeq(content, timeout);
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
          int docInIndex = docInIndexes[docIdx]; // original input order
          assert resultByDocIn[docInIndex] == null;

          int snippetLimit =
              Math.max(
                  maxPassages[fieldIdx],
                  params.getInt(OcrHighlightParams.MAX_OCR_PASSAGES, DEFAULT_SNIPPET_LIMIT));

          // Final aliases for lambda
          final int docIdFinal = docId;
          final int fieldIdxFinal = fieldIdx;
          final IterableCharSequence contentFinal = content;
          Runnable hlFn =
              () -> {
                try {
                  highlightDocField(
                      docIdFinal,
                      docInIndex,
                      fieldIdxFinal,
                      contentFinal,
                      fieldHighlighter,
                      leafReader,
                      snippetLimit,
                      resultByDocIn,
                      snippetCountsByField);
                } catch (ExitingIterCharSeq.ExitingIterCharSeqException
                    | ExitableDirectoryReader.ExitingReaderException e) {
                  resultByDocIn[docInIndex] = null;
                  throw e;
                } catch (IOException | RuntimeException e) {
                  // This catch-all prevents OCR highlighting from failing the complete query,
                  // instead users get an error message in their Solr log.
                  if (contentFinal.getPointer() != null) {
                    log.error(
                        "Could not highlight OCR content for document {} at '{}'",
                        docIdFinal,
                        contentFinal.getPointer(),
                        e);
                  } else {
                    log.error(
                        "Could not highlight OCR for document {} with OCR markup '{}...'",
                        docIdFinal,
                        contentFinal.subSequence(0, 256),
                        e);
                  }
                } finally {
                  if (contentFinal instanceof AutoCloseable) {
                    try {
                      ((AutoCloseable) contentFinal).close();
                    } catch (Exception e) {
                      log.warn(
                          "Encountered error while closing content iterator for {}: {}",
                          contentFinal.getPointer(),
                          e.getMessage());
                    }
                  }
                }
              };
          try {
            // Speed up highlighting by parallelizing the work as much as possible
            hlFuts.add(CompletableFuture.runAsync(hlFn, hlThreadPool));
          } catch (RejectedExecutionException rejected) {
            // If the pool is full, run the task synchronously on the current thread
            try {
              hlFn.run();
            } catch (ExitingIterCharSeq.ExitingIterCharSeqException
                | ExitableDirectoryReader.ExitingReaderException e) {
              if (respHeader.get(PARTIAL_OCR_HIGHLIGHTS) == null) {
                respHeader.put(PARTIAL_OCR_HIGHLIGHTS, Boolean.TRUE);
                log.warn("OCR Highlighting timed out while handling " + content.getPointer(), e);
              }
              resultByDocIn[docInIndex] = null;
              break docLoop;
            }
          }
        }
      }
      batchDocIdx += fieldValsByDoc.size();
    }
    assert docIdIter.docID() == DocIdSetIterator.NO_MORE_DOCS
        || docIdIter.nextDoc() == DocIdSetIterator.NO_MORE_DOCS;

    if (!hlFuts.isEmpty()) {
      CompletableFuture<?>[] futArray = hlFuts.toArray(new CompletableFuture[0]);
      CompletableFuture<Void> allFut = CompletableFuture.allOf(futArray);
      try {
        allFut.join();
      } catch (CompletionException e) {
        if (e.getCause() instanceof ExitingIterCharSeq.ExitingIterCharSeqException
            || e.getCause() instanceof ExitableDirectoryReader.ExitingReaderException) {
          respHeader.put(PARTIAL_OCR_HIGHLIGHTS, Boolean.TRUE);
        } else {
          log.error("Error while highlighting OCR content", e);
        }
      }
    }

    OcrHighlightResult[] out = new OcrHighlightResult[docIds.length];
    for (int d = 0; d < docIds.length; d++) {
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

  private void highlightDocField(
      int docId,
      int docInIndex,
      int fieldIdx,
      IterableCharSequence content,
      OcrFieldHighlighter fieldHighlighter,
      LeafReader leafReader,
      int snippetLimit,
      OcrSnippet[][] resultByDocIn,
      int[][] snippetCountsByField)
      throws IOException {
    if (content == null) {
      return;
    }
    OcrFormat ocrFormat = getFormat(content);
    if (ocrFormat == null) {
      return;
    }

    String limitBlockParam = params.get(OcrHighlightParams.LIMIT_BLOCK, "block");
    OcrBlock[] limitBlocks = null;
    if (!limitBlockParam.equalsIgnoreCase("NONE")) {
      limitBlocks =
          OcrBlock.getHierarchyFrom(OcrBlock.valueOf(limitBlockParam.toUpperCase(Locale.US)))
              .toArray(new OcrBlock[0]);
    }
    OcrBlock contextBlock =
        OcrBlock.valueOf(
            params.get(OcrHighlightParams.CONTEXT_BLOCK, "line").toUpperCase(Locale.US));

    BreakLocator contextLocator = ocrFormat.getBreakLocator(content, contextBlock);
    BreakLocator limitLocator =
        limitBlocks == null ? null : ocrFormat.getBreakLocator(content, limitBlocks);
    BreakLocator breakLocator =
        new ContextBreakLocator(
            contextLocator, limitLocator, params.getInt(OcrHighlightParams.CONTEXT_SIZE, 2));
    OcrPassageFormatter formatter =
        ocrFormat.getPassageFormatter(
            OcrHighlightParams.get(params, OcrHighlightParams.TAG_PRE, "<em>"),
            OcrHighlightParams.get(params, OcrHighlightParams.TAG_POST, "</em>"),
            params.getBool(OcrHighlightParams.ABSOLUTE_HIGHLIGHTS, false),
            params.getBool(OcrHighlightParams.ALIGN_SPANS, false),
            params.getBool(OcrHighlightParams.TRACK_PAGES, true));
    boolean scorePassages = params.getBool(OcrHighlightParams.SCORE_PASSAGES, true);

    resultByDocIn[docInIndex] =
        fieldHighlighter.highlightFieldForDoc(
            leafReader,
            docId,
            breakLocator,
            formatter,
            content,
            params.get(OcrHighlightParams.PAGE_ID),
            snippetLimit,
            scorePassages);
    snippetCountsByField[fieldIdx][docInIndex] = fieldHighlighter.getNumMatches(docId);
  }

  @Override
  protected List<CharSequence[]> loadFieldValues(
      String[] fields, DocIdSetIterator docIter, int cacheCharsThreshold) throws IOException {
    return loadOcrFieldValues(fields, docIter).stream()
        .map(
            seqs ->
                Arrays.stream(seqs)
                    .map(IterableCharSequence::toString)
                    .toArray(CharSequence[]::new))
        .collect(Collectors.toList());
  }

  protected List<IterableCharSequence[]> loadOcrFieldValues(
      String[] fields, DocIdSetIterator docIter) throws IOException {
    List<IterableCharSequence[]> fieldValues = new ArrayList<>((int) docIter.cost());
    int docId;
    while ((docId = docIter.nextDoc()) != DocIdSetIterator.NO_MORE_DOCS) {
      DocumentStoredFieldVisitor docIdVisitor = new DocumentStoredFieldVisitor(fields);
      IterableCharSequence[] ocrVals = new IterableCharSequence[fields.length];
      searcher.doc(docId, docIdVisitor);
      for (int fieldIdx = 0; fieldIdx < fields.length; fieldIdx++) {
        String fieldName = fields[fieldIdx];
        String fieldValue = docIdVisitor.getDocument().get(fieldName);
        if (fieldValue == null) {
          // No OCR content at all
          ocrVals[fieldIdx] = null;
          continue;
        }
        if (!SourcePointer.isPointer(fieldValue)) {
          // OCR content as stored text
          ocrVals[fieldIdx] = IterableCharSequence.fromString(fieldValue);
          continue;
        }
        SourcePointer sourcePointer = null;
        try {
          sourcePointer = SourcePointer.parse(fieldValue);
        } catch (RuntimeException e) {
          log.error("Could not parse OCR pointer for document {}: {}", docId, fieldValue, e);
        }
        if (sourcePointer == null) {
          // None of the files in the pointer exist or were readable, log should have warnings
          ocrVals[fieldIdx] = null;
          continue;
        }
        if (sourcePointer.sources.size() == 1) {
          ocrVals[fieldIdx] =
              new FileBytesCharIterator(
                  sourcePointer.sources.get(0).path, StandardCharsets.UTF_8, sourcePointer);
        } else {
          ocrVals[fieldIdx] =
              new MultiFileBytesCharIterator(
                  sourcePointer.sources.stream().map(s -> s.path).collect(Collectors.toList()),
                  StandardCharsets.UTF_8,
                  sourcePointer);
        }
      }
      fieldValues.add(ocrVals);
    }
    return fieldValues;
  }

  private OcrFormat getFormat(IterableCharSequence content) {
    // Sample the first 4k characters to determine the format
    String sampleChunk = content.subSequence(0, Math.min(4096, content.length())).toString();
    return formats.stream().filter(fmt -> fmt.hasFormat(sampleChunk)).findFirst().orElse(null);
  }

  /**
   * Configure the field highlighter.
   *
   * <p>Heavily based on {@link UnifiedHighlighter#getFieldHighlighter(String, Query, Set, int)} and
   * {@link UnifiedHighlighter#getHighlightComponents(String, Query, Set)}, modified to integrate it
   * into our custom OCR highlighting setup. <strong>Please refer to the file header for licensing
   * information on the original code.</strong>
   */
  private OcrFieldHighlighter getOcrFieldHighlighter(
      String field, Query query, Set<Term> allTerms, int maxPassages) {
    // This method and some associated types changed in v8.2 and v8.4, so we have to delegate to an
    // adapter method for these versions
    if (VERSION_IS_PRE84) {
      return getOcrFieldHighlighterLegacy(field, query, allTerms, maxPassages);
    }

    Predicate<String> fieldMatcher = getFieldMatcher(field);
    BytesRef[] terms = filterExtractedTerms(fieldMatcher, allTerms);
    Set<HighlightFlag> highlightFlags = getFlags(field);
    PhraseHelper phraseHelper = getPhraseHelper(field, query, highlightFlags);
    LabelledCharArrayMatcher[] automata = getAutomata(field, query, highlightFlags);

    UHComponents components =
        new UHComponents(
            field,
            fieldMatcher,
            query,
            terms,
            phraseHelper,
            automata,
            hasUnrecognizedQuery(fieldMatcher, query),
            highlightFlags);
    OffsetSource offsetSource = getOptimizedOffsetSource(components);
    return new OcrFieldHighlighter(
        field,
        getOffsetStrategy(offsetSource, components),
        getScorer(field),
        maxPassages,
        getMaxNoHighlightPassages(field));
  }

  private OcrFieldHighlighter getOcrFieldHighlighterLegacy(
      String field, Query query, Set<Term> allTerms, int maxPassages) {
    Predicate<String> fieldMatcher = getFieldMatcher(field);
    BytesRef[] terms = filterExtractedTerms(fieldMatcher, allTerms);
    Set<HighlightFlag> highlightFlags = getFlags(field);
    PhraseHelper phraseHelper = getPhraseHelper(field, query, highlightFlags);
    CharacterRunAutomaton[] automata = getAutomataLegacy(field, query, highlightFlags);

    // Obtaining these two values has changed with Solr 8.2, so we need to do some reflection for
    // older versions
    OffsetSource offsetSource;
    UHComponents components;
    if (VERSION_IS_PRE82) {
      offsetSource = this.getOffsetSourcePre82(field, terms, phraseHelper, automata);
      components =
          this.getUHComponentsPre82(
              field, fieldMatcher, query, terms, phraseHelper, automata, highlightFlags);
    } else {
      components =
          this.getUHComponentsPre84(
              field, fieldMatcher, query, terms, phraseHelper, automata, highlightFlags);
      offsetSource = this.getOptimizedOffsetSource(components);
    }
    return new OcrFieldHighlighter(
        field,
        getOffsetStrategy(offsetSource, components),
        getScorer(field),
        maxPassages,
        getMaxNoHighlightPassages(field));
  }

  private CharacterRunAutomaton[] getAutomataLegacy(
      String field, Query query, Set<HighlightFlag> highlightFlags) {
    // do we "eagerly" look in span queries for automata here, or do we not and let PhraseHelper
    // handle those?
    // if don't highlight phrases strictly,
    final boolean lookInSpan =
        !highlightFlags.contains(HighlightFlag.PHRASES) // no PhraseHelper
            || highlightFlags.contains(
                HighlightFlag.WEIGHT_MATCHES); // Weight.Matches will find all

    return highlightFlags.contains(HighlightFlag.MULTI_TERM_QUERY)
        ? extractAutomataLegacy(query, getFieldMatcher(field), lookInSpan)
        : ZERO_LEN_AUTOMATA_ARRAY_LEGACY;
  }

  private CharacterRunAutomaton[] extractAutomataLegacy(
      Query query, Predicate<String> fieldMatcher, boolean lookInSpan) {
    Function<Query, Collection<Query>> nopWriteFn = q -> null;
    try {
      if (VERSION_IS_PRE81) {
        return (CharacterRunAutomaton[])
            extractAutomataLegacyMethod.invoke(null, query, fieldMatcher, lookInSpan, nopWriteFn);
      } else {
        return (CharacterRunAutomaton[])
            extractAutomataLegacyMethod.invoke(null, query, fieldMatcher, lookInSpan);
      }
    } catch (IllegalAccessException | InvocationTargetException e) {
      throw new RuntimeException(e);
    }
  }

  private OffsetSource getOffsetSourcePre82(
      String field, BytesRef[] terms, PhraseHelper phraseHelper, CharacterRunAutomaton[] automata) {
    try {
      return (OffsetSource)
          offsetSourceGetterLegacy.invoke(this, field, terms, phraseHelper, automata);
    } catch (IllegalAccessException | InvocationTargetException e) {
      throw new RuntimeException(e);
    }
  }

  private UHComponents getUHComponentsPre82(
      String field,
      Predicate<String> fieldMatcher,
      Query query,
      BytesRef[] terms,
      PhraseHelper phraseHelper,
      CharacterRunAutomaton[] automata,
      Set<HighlightFlag> highlightFlags) {
    try {
      return hlComponentsConstructorLegacy.newInstance(
          field, fieldMatcher, query, terms, phraseHelper, automata, highlightFlags);
    } catch (IllegalAccessException | InvocationTargetException | InstantiationException e) {
      throw new RuntimeException(e);
    }
  }

  private UHComponents getUHComponentsPre84(
      String field,
      Predicate<String> fieldMatcher,
      Query query,
      BytesRef[] terms,
      PhraseHelper phraseHelper,
      CharacterRunAutomaton[] automata,
      Set<HighlightFlag> highlightFlags) {
    try {
      return hlComponentsConstructorLegacy.newInstance(
          field,
          fieldMatcher,
          query,
          terms,
          phraseHelper,
          automata,
          hasUnrecognizedQuery(fieldMatcher, query),
          highlightFlags);
    } catch (ReflectiveOperationException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * This is copied straight from {@link
   * UnifiedHighlighter#copyAndSortFieldsWithMaxPassages(String[], int[], String[], int[])} because
   * it has private access there. <strong>Please refer to the file header for licensing information
   * on the original code.</strong>
   */
  private void copyAndSortFieldsWithMaxPassages(
      String[] fieldsIn, int[] maxPassagesIn, final String[] fields, final int[] maxPassages) {
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

  /**
   * This is copied straight from {@link UnifiedHighlighter#copyAndSortDocIdsWithIndex(int[], int[],
   * int[])} )} because it has private access there. <strong>Please refer to the file header for
   * licensing information on the original code.</strong>
   */
  private void copyAndSortDocIdsWithIndex(
      int[] docIdsIn, final int[] docIds, final int[] docInIndexes) {
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

  /**
   * This is copied straight from {@link UnifiedHighlighter#asDocIdSetIterator(int[])} )} because it
   * has private access there. <strong>Please refer to the file header for licensing information on
   * the original code.</strong>
   */
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
      public int nextDoc() {
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
   * Wraps an IndexReader that remembers/caches the last call to {@link
   * LeafReader#getTermVectors(int)} so that if the next call has the same ID, then it is reused. If
   * TV's were column-stride (like doc-values), there would be no need for this.
   *
   * <p>This is copied straight from {@link UnifiedHighlighter#asDocIdSetIterator(int[])} )} because
   * it has private access there. <strong>Please refer to the file header for licensing information
   * on the original code.</strong>
   */
  private static class TermVectorReusingLeafReader extends FilterLeafReader {

    static IndexReader wrap(IndexReader reader) throws IOException {
      LeafReader[] leafReaders =
          reader.leaves().stream()
              .map(LeafReaderContext::reader)
              .map(TermVectorReusingLeafReader::new)
              .toArray(LeafReader[]::new);
      if (VERSION_IS_PRE89) {
        return new LegacyBaseCompositeReader<IndexReader>(leafReaders) {
          @Override
          protected void doClose() throws IOException {
            reader.close();
          }

          @Override
          public CacheHelper getReaderCacheHelper() {
            return null;
          }
        };
      } else {
        return new BaseCompositeReader<IndexReader>(leafReaders, null) {
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
