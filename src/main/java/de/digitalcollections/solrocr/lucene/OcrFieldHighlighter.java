package de.digitalcollections.solrocr.lucene;

import de.digitalcollections.solrocr.iter.BreakLocator;
import de.digitalcollections.solrocr.iter.IterableCharSequence;
import de.digitalcollections.solrocr.model.OcrSnippet;
import de.digitalcollections.solrocr.util.PageCacheWarmer;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.PriorityQueue;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.search.uhighlight.FieldHighlighter;
import org.apache.lucene.search.uhighlight.FieldOffsetStrategy;
import org.apache.lucene.search.uhighlight.OffsetsEnum;
import org.apache.lucene.search.uhighlight.Passage;
import org.apache.lucene.search.uhighlight.PassageScorer;
import org.apache.lucene.util.BytesRef;

/**
 * A customization of {@link FieldHighlighter} to support OCR fields
 */
public class OcrFieldHighlighter extends FieldHighlighter {
  private final Map<Integer, Integer> numMatches;

  public OcrFieldHighlighter(String field, FieldOffsetStrategy fieldOffsetStrategy,
                             PassageScorer passageScorer, int maxPassages, int maxNoHighlightPassages) {
    super(field, fieldOffsetStrategy, null, passageScorer, maxPassages, maxNoHighlightPassages, null);
    this.numMatches = new HashMap<>();
  }

  /**
   * The primary method -- highlight this doc, assuming a specific field and given this content.
   *
   * Largely copied from {@link FieldHighlighter#highlightFieldForDoc(LeafReader, int, String)}, modified to support
   * an {@link IterableCharSequence} as content and dynamically setting the break iterator and the formatter.
   */
  public OcrSnippet[] highlightFieldForDoc(LeafReader reader, int docId, BreakLocator breakLocator,
                                           OcrPassageFormatter formatter, IterableCharSequence content, String pageId,
                                           int snippetLimit)
      throws IOException {
    // note: it'd be nice to accept a CharSequence for content, but we need a CharacterIterator impl for it.

    // If page cache pre-warming is enabled, cancel it, since we're doing the I/O ourselves now
    PageCacheWarmer.getInstance().ifPresent(w -> w.cancelPreload(content.getPointer()));
    if (content.length() == 0) {
      return null; // nothing to do
    }

    Passage[] passages;
    try (OffsetsEnum offsetsEnums = fieldOffsetStrategy.getOffsetsEnum(reader, docId, null)) {
      passages = highlightOffsetsEnums(offsetsEnums, docId, breakLocator, formatter, pageId, snippetLimit);
    }

    // Format the resulting Passages.
    if (passages.length == 0 && pageId == null) {
      // no passages were returned, so ask for a default summary
      passages = getSummaryPassagesNoHighlight(maxNoHighlightPassages == -1 ? maxPassages : maxNoHighlightPassages);
    }

    if (passages.length > 0) {
      OcrSnippet[] snippets = formatter.format(passages, content);
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

  protected Passage[] highlightOffsetsEnums(
      OffsetsEnum off, int docId, BreakLocator breakLocator, OcrPassageFormatter formatter, String pageId,
      int snippetLimit) throws IOException {
    final int contentLength = breakLocator.getText().getEndIndex();
    if (!off.nextPosition()) {
      return new Passage[0];
    }
    // If we're filtering by a page identifier, we want *all* hits on that page
    int queueSize = pageId != null ? 4096 : maxPassages;
    if (queueSize  <= 0) {
      queueSize = 512;
    }

    PriorityQueue<Passage> passageQueue = new PriorityQueue<>(queueSize, (left, right) -> {
      if (left.getScore() < right.getScore()) {
        return -1;
      } else if (left.getScore() > right.getScore()) {
        return 1;
      } else {
        return left.getStartOffset() - right.getStartOffset();
      }
    });
    Passage passage = new Passage(); // the current passage in-progress.  Will either get reset or added to queue.

    // If we've reached the limit, no longer calculate passages, only count matches as passages
    boolean limitReached = false;
    int numTotal = 0;
    do {
      int start = off.startOffset();
      if (start == -1) {
        throw new IllegalArgumentException("field '" + field + "' was indexed without offsets, cannot highlight");
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
      // Since building passages is expensive when using external files, we forego it past a certain limit
      // (which can be set by the user) and just update the total count, counting each match as a single passage.
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
        passage = maybeAddPassage(passageQueue, passageScorer, passage, contentLength);
        // if we exceed limit, we are done
        if (start >= contentLength) {
          break;
        }
        passage.setStartOffset(passageStart);
      }
      passage.setEndOffset(passageEnd);
      // Add this term to the passage.
      BytesRef term = off.getTerm();// a reference; safe to refer to
      assert term != null;
      passage.addMatch(start, end, term, off.freq());
    } while (off.nextPosition());
    if (passage.getStartOffset() >= 0) {
      numTotal++;
    }
    maybeAddPassage(passageQueue, passageScorer, passage, contentLength);

    this.numMatches.put(docId, numTotal);
    Passage[] passages = passageQueue.toArray(new Passage[passageQueue.size()]);
    // sort in ascending order
    Arrays.sort(passages, Comparator.comparingInt(Passage::getStartOffset));
    return passages;
  }

  /** Completely copied from {@link FieldHighlighter} due to private access there. */
  private Passage maybeAddPassage(PriorityQueue<Passage> passageQueue, PassageScorer scorer, Passage passage,
                                  int contentLength) {
    if (passage.getStartOffset() == -1) {
      // empty passage, we can ignore it
      return passage;
    }
    passage.setScore(scorer.score(passage, contentLength));
    // new sentence: first add 'passage' to queue
    if (passageQueue.size() == maxPassages && passage.getScore() < passageQueue.peek().getScore()) {
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
    return new Passage[]{};
  }

  public int getNumMatches(int  docId) {
    return numMatches.getOrDefault(docId, -1);
  }
}
