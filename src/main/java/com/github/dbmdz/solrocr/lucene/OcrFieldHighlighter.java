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
package com.github.dbmdz.solrocr.lucene;

import com.github.dbmdz.solrocr.breaklocator.BreakLocator;
import com.github.dbmdz.solrocr.model.OcrSnippet;
import com.github.dbmdz.solrocr.reader.SourceReader;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.search.uhighlight.FieldHighlighter;
import org.apache.lucene.search.uhighlight.FieldOffsetStrategy;
import org.apache.lucene.search.uhighlight.OffsetsEnum;
import org.apache.lucene.search.uhighlight.Passage;
import org.apache.lucene.search.uhighlight.PassageScorer;
import org.apache.lucene.search.uhighlight.UnifiedHighlighter;
import org.apache.lucene.util.BytesRef;

/** A customization of {@link FieldHighlighter} to support OCR fields */
public class OcrFieldHighlighter {
  private final ConcurrentHashMap<Integer, Integer> numMatches;

  private final String field;
  private final FieldOffsetStrategy fieldOffsetStrategy;
  private final PassageScorer passageScorer;
  private final int maxPassages;
  private final int maxNoHighlightPassages;

  public OcrFieldHighlighter(
      String field,
      FieldOffsetStrategy fieldOffsetStrategy,
      PassageScorer passageScorer,
      int maxPassages,
      int maxNoHighlightPassages) {
    this.numMatches = new ConcurrentHashMap<>();
    this.field = field;
    this.fieldOffsetStrategy = fieldOffsetStrategy;
    this.passageScorer = passageScorer;
    this.maxPassages = maxPassages;
    this.maxNoHighlightPassages = maxNoHighlightPassages;
  }

  /**
   * The primary method -- highlight this doc, assuming a specific field and given this content.
   *
   * <p>Largely copied from {@link FieldHighlighter#highlightFieldForDoc(LeafReader, int, String)},
   * modified to support an {@link SourceReader} as content and dynamically setting the break
   * iterator and the formatter. <strong>Please refer to the file header for licensing information
   * on the original code.</strong>
   */
  public OcrSnippet[] highlightFieldForDoc(
      LeafReader reader,
      int indexDocId, // relative to the whole index
      int readerDocId, // relative to the current leafReader
      BreakLocator breakLocator,
      OcrPassageFormatter formatter,
      SourceReader content,
      String pageId,
      int snippetLimit,
      boolean scorePassages)
      throws IOException {
    // note: it'd be nice to accept a CharSequence for content, but we need a CharacterIterator impl
    // for it.

    if (content.length() == 0) {
      return null; // nothing to do
    }

    Passage[] passages;
    try (OffsetsEnum offsetsEnums = fieldOffsetStrategy.getOffsetsEnum(reader, readerDocId, null)) {
      passages =
          highlightOffsetsEnums(
              offsetsEnums,
              indexDocId,
              breakLocator,
              formatter,
              pageId,
              snippetLimit,
              scorePassages);
    }

    // Format the resulting Passages.
    if (passages.length == 0 && pageId == null) {
      // no passages were returned, so ask for a default summary
      passages =
          getSummaryPassagesNoHighlight(
              maxNoHighlightPassages == -1 ? maxPassages : maxNoHighlightPassages);
    }

    if (passages.length > 0) {
      OcrSnippet[] snippets = formatter.format(passages, breakLocator.getText());
      Arrays.sort(snippets, Collections.reverseOrder());
      return snippets;
    } else {
      return null;
    }
  }

  protected Passage[] highlightOffsetsEnums(OffsetsEnum off) {
    throw new UnsupportedOperationException();
  }

  /**
   * Aggregate matches into passages, optionally scoring them.
   *
   * <p>Largely based on {@link FieldHighlighter#highlightOffsetsEnums(OffsetsEnum)} with
   * modifications to add support for the {@link BreakLocator} interface, the option to disable
   * scoring, the option to limit the number of snippets to consider for scoring as well as
   * restricting the returned snippets to those from OCR pages with a given identifier. <strong>
   * Please refer to the file header for licensing information on the original code.</strong>
   *
   * @param off the {@link OffsetsEnum} to retrieve matches with their offsets from
   * @param indexDocId the document ID in the index
   * @param breakLocator the {@link BreakLocator} to use for determining the passage boundaries,
   *     also used for obtaining the text, which is bound to it
   * @param formatter the {@link OcrPassageFormatter} to use for determining the page identifier in
   *     case we are only intersted in a single page (i.e. if {@code pageId}) is non-null
   * @param pageId the page identifier to restrict the results to, or null if all pages should be
   *     considered
   * @param snippetLimit the maximum number of snippets to consider for scoring, after this number
   *     has been reached, no more snippets will be considered for the output (the total count is
   *     still being incremented, though).
   * @param scorePassages Flag to indicate whether to score the passages or not. If false, the
   *     passages will be returned in the order they were found, without any scoring, otherwise they
   *     will be scored with BM25 and returned in descending order of their score.
   * @return the passages that were found in the given content, ordered by score (if {@code
   *     scorePassages}) or by their order of appearance (if {@code !scorePassages})
   */
  protected Passage[] highlightOffsetsEnums(
      OffsetsEnum off,
      int indexDocId,
      BreakLocator breakLocator,
      OcrPassageFormatter formatter,
      String pageId,
      int snippetLimit,
      boolean scorePassages)
      throws IOException {
    final int contentLength = breakLocator.getText().length();
    if (!off.nextPosition()) {
      return new Passage[0];
    }
    int queueSize = maxPassages;
    if (!scorePassages) {
      // If we're not scoring, we are only interested in the first matches until we reach the
      // snippetLimit
      queueSize = snippetLimit;
    } else if (pageId != null) {
      // If we're filtering by a page identifier, we want to take *all* hits on that page into
      // account
      queueSize = 4096;
    }
    if (queueSize <= 0) {
      queueSize = 512;
    }

    Comparator<Passage> cmp;
    if (scorePassages) {
      cmp =
          (left, right) -> {
            if (left.getScore() < right.getScore()) {
              return -1;
            } else if (left.getScore() > right.getScore()) {
              return 1;
            } else {
              return left.getStartOffset() - right.getStartOffset();
            }
          };
    } else {
      cmp = Comparator.comparingInt(Passage::getStartOffset);
    }

    PriorityQueue<Passage> passageQueue = new PriorityQueue<>(queueSize, cmp);
    Passage passage =
        new Passage(); // the current passage in-progress.  Will either get reset or added to queue.

    // Since building passages is expensive when using external files, we forego it past a certain
    // limit (which can be set by the user) and just update the total count, counting each match
    // as a single passage.
    boolean limitReached = false;
    int numTotal = 0;
    do {
      limitReached = limitReached || numTotal >= snippetLimit;
      int start = off.startOffset();
      if (start == -1) {
        throw new IllegalArgumentException(
            "field '" + field + "' was indexed without offsets, cannot highlight");
      }
      if (pageId != null) {
        String passagePageId = formatter.determineStartPage(start, breakLocator.getText()).id;
        if (!passagePageId.equals(pageId)) {
          continue;
        }
      }
      int end = off.endOffset();
      if (start < contentLength && end > contentLength) {
        continue;
      }
      if (limitReached) {
        // Only count the match, but don't build a passage
        numTotal++;
        continue;
      }
      // advance breakIterator
      int passageStart = Math.max(breakLocator.preceding(start + 1), 0);
      int passageEnd = Math.min(breakLocator.following(end), contentLength);

      // See if this term should be part of a new passage.
      if (passageStart >= passage.getEndOffset()) {
        if (passage.getStartOffset() >= 0) {
          numTotal++;
        }
        passage =
            maybeAddPassage(passageQueue, passageScorer, passage, contentLength, scorePassages);
        // if we exceed the content size, we are done
        if (start >= contentLength) {
          break;
        }
        passage.setStartOffset(passageStart);
      }
      passage.setEndOffset(passageEnd);
      // Add this term to the passage.
      BytesRef term = off.getTerm(); // a reference; safe to refer to
      assert term != null;
      passage.addMatch(start, end, term, off.freq());
    } while (off.nextPosition());
    if (passage.getStartOffset() >= 0) {
      numTotal++;
    }
    maybeAddPassage(passageQueue, passageScorer, passage, contentLength, scorePassages);

    this.numMatches.put(indexDocId, numTotal);
    Passage[] passages = passageQueue.toArray(new Passage[passageQueue.size()]);
    // Array has unspecified order, sort again
    if (scorePassages) {
      // We want the highest scoring passage first
      Arrays.sort(passages, Collections.reverseOrder(cmp));
    } else {
      // We want the snippets in their order of appearance
      Arrays.sort(passages, cmp);
    }
    return passages;
  }

  /**
   * Largely identical to {@link FieldHighlighter#maybeAddPassage(PriorityQueue, PassageScorer,
   * Passage, int)}.
   *
   * <p>This was copied due to private access in the upstream code and to add support for disabling
   * scoring. <strong>Please refer to the file header for licensing information on the original
   * code.</strong>
   */
  private Passage maybeAddPassage(
      PriorityQueue<Passage> passageQueue,
      PassageScorer scorer,
      Passage passage,
      int contentLength,
      boolean score) {
    if (passage.getStartOffset() == -1) {
      // empty passage, we can ignore it
      return passage;
    }
    if (score) {
      passage.setScore(scorer.score(passage, contentLength));
    }
    boolean queueIsFull = passageQueue.size() == maxPassages;
    if (score) {
      if (queueIsFull && passage.getScore() < passageQueue.peek().getScore()) {
        // If the queue is full, and the score of the passage is below the lowest scoring passage,
        // we just reset the passage and return it.
        passage.reset();
        return passage;
      }
      // Queue not full, or the passage is better than the worst passage in the queue
      passageQueue.add(passage);
      boolean queueExceedsMax = passageQueue.size() > maxPassages;
      if (queueExceedsMax) {
        // If the queue exceeds the max size, we remove the lowest scoring passage and re-use
        // it, to avoid a heap allocation
        passage = passageQueue.poll();
        passage.reset();
      } else {
        passage = new Passage();
      }
    } else {
      // With scoring disabled, we're only interested in the earliest matches in the doc and ignore
      // the rest.
      if (!queueIsFull) {
        passageQueue.add(passage);
        passage = new Passage();
      } else {
        passage.reset();
        return passage;
      }
    }
    return passage;
  }

  /** We don't provide summaries if there is no highlighting, i.e. no matches in the OCR text */
  protected Passage[] getSummaryPassagesNoHighlight(int maxPassages) {
    return new Passage[] {};
  }

  public UnifiedHighlighter.OffsetSource getOffsetSource() {
    return this.fieldOffsetStrategy.getOffsetSource();
  }

  public int getNumMatches(int docId) {
    return numMatches.getOrDefault(docId, -1);
  }
}
