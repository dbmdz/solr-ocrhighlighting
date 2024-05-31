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
import org.apache.lucene.util.BytesRef;

/** A customization of {@link FieldHighlighter} to support OCR fields */
public class OcrFieldHighlighter extends FieldHighlighter {
  private final ConcurrentHashMap<Integer, Integer> numMatches;

  public OcrFieldHighlighter(
      String field,
      FieldOffsetStrategy fieldOffsetStrategy,
      PassageScorer passageScorer,
      int maxPassages,
      int maxNoHighlightPassages) {
    super(
        field, fieldOffsetStrategy, null, passageScorer, maxPassages, maxNoHighlightPassages, null);
    this.numMatches = new ConcurrentHashMap<>();
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

  @Override
  protected Passage[] highlightOffsetsEnums(OffsetsEnum off) {
    throw new UnsupportedOperationException();
  }

  /**
   * Score snippets as mini-documents, either based on TF-IDF/BM25 or simply their position.
   *
   * <p>Largely based on {@link FieldHighlighter#highlightOffsetsEnums(OffsetsEnum)} with
   * modifications to add support for the {@link BreakLocator} interrface, the option to disable
   * scoring, the option to limit the number of snippets to consider for scoring as well as
   * restricting the returned snippets to those from OCR pages with a given identifier. <strong>
   * Please refer to the file header for licensing information on the original code.</strong>
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
    // If we're filtering by a page identifier, we want *all* hits on that page
    int queueSize = pageId != null ? 4096 : maxPassages;
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

    // If we've reached the limit, no longer calculate passages, only count matches as passages
    boolean limitReached = false;
    int numTotal = 0;
    do {
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
      // Since building passages is expensive when using external files, we forego it past a certain
      // limit (which can be set by the user) and just update the total count, counting each match
      // as a single passage.
      if (limitReached || numTotal > snippetLimit) {
        numTotal++;
        limitReached = true;
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
        // if we exceed limit, we are done
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
    // sort in ascending order
    Arrays.sort(passages, Comparator.comparingInt(Passage::getStartOffset));
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
    // new sentence: first add 'passage' to queue
    if (score
        && passageQueue.size() == maxPassages
        && passage.getScore() < passageQueue.peek().getScore()) {
      passage.reset(); // can't compete, just reset it
    } else {
      passageQueue.offer(passage);
      if (passageQueue.size() > maxPassages) {
        passage = passageQueue.poll();
        passage.reset();
      } else {
        passage = new Passage();
      }
    }
    return passage;
  }

  /** We don't provide summaries if there is no highlighting, i.e. no matches in the OCR text */
  @Override
  protected Passage[] getSummaryPassagesNoHighlight(int maxPassages) {
    return new Passage[] {};
  }

  public int getNumMatches(int docId) {
    return numMatches.getOrDefault(docId, -1);
  }
}
