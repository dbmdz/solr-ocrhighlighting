package de.digitalcollections.solrocr.lucene;

import de.digitalcollections.solrocr.formats.OcrPassageFormatter;
import de.digitalcollections.solrocr.formats.OcrSnippet;
import de.digitalcollections.solrocr.lucene.byteoffset.ByteOffsetsEnum;
import de.digitalcollections.solrocr.lucene.byteoffset.FieldByteOffsetStrategy;
import de.digitalcollections.solrocr.util.IterableCharSequence;
import de.digitalcollections.solrocr.util.Utf8;
import java.io.IOException;
import java.text.BreakIterator;
import java.util.Arrays;
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
 * A customization of {@link FieldHighlighter} to support lazy-loaded field values and byte offsets from payloads.
 */
public class OcrFieldHighlighter extends FieldHighlighter {
  protected FieldByteOffsetStrategy fieldByteOffsetStrategy;
  private Map<Integer, Integer> numMatches;

  public OcrFieldHighlighter(String field, FieldOffsetStrategy fieldOffsetStrategy,
                             FieldByteOffsetStrategy fieldByteOffsetStrategy, PassageScorer passageScorer,
                             BreakIterator breakIter, OcrPassageFormatter formatter, int maxPassages,
                             int maxNoHighlightPassages) {
    super(field, fieldOffsetStrategy, breakIter, passageScorer, maxPassages, maxNoHighlightPassages, formatter);
    this.fieldByteOffsetStrategy = fieldByteOffsetStrategy;
    this.numMatches = new HashMap<>();
  }

  /**
   * The primary method -- highlight this doc, assuming a specific field and given this content.
   *
   * Largely copied from {@link FieldHighlighter#highlightFieldForDoc(LeafReader, int, String)}, modified to support
   * an {@link IterableCharSequence} as content.
   */
  public OcrSnippet[] highlightFieldForDoc(LeafReader reader, int docId, IterableCharSequence content, String pageId)
      throws IOException {
    // note: it'd be nice to accept a CharSequence for content, but we need a CharacterIterator impl for it.
    if (content.length() == 0) {
      return null; // nothing to do
    }

    breakIterator.setText(content);

    Passage[] passages;
    try (OffsetsEnum offsetsEnums = fieldOffsetStrategy.getOffsetsEnum(reader, docId, null)) {
      passages = highlightOffsetsEnums(offsetsEnums, docId, pageId);// and breakIterator & scorer
    }

    // Format the resulting Passages.
    if (passages.length == 0 && pageId == null) {
      // no passages were returned, so ask for a default summary
      passages = getSummaryPassagesNoHighlight(maxNoHighlightPassages == -1 ? maxPassages : maxNoHighlightPassages);
    }

    if (passages.length > 0) {
      return ((OcrPassageFormatter) passageFormatter).format(passages, content);
    } else {
      return null;
    }
  }
  @Override
  protected Passage[] highlightOffsetsEnums(OffsetsEnum off) throws IOException {
    return this.highlightOffsetsEnums(off, -1, null);
  }

  protected Passage[] highlightOffsetsEnums(OffsetsEnum off, int docId, String pageId) throws IOException {
        final int contentLength = this.breakIterator.getText().getEndIndex();
    if (!off.nextPosition()) {
      return new Passage[0];
    }
    // If we're filtering by a page identifier, we want *all* hits on that page
    int queueSize = pageId != null ? 4096 : maxPassages;
    if (queueSize  <= 0) {
      queueSize = 512;
    }
    queueSize = Math.min(512, queueSize);

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

    int numTotal = 0;
    do {
      int start = off.startOffset();
      if (start == -1) {
        throw new IllegalArgumentException("field '" + field + "' was indexed without offsets, cannot highlight");
      }
      if (pageId != null) {
        String passagePageId = ((OcrPassageFormatter) passageFormatter).determineStartPage(
            null, start, (IterableCharSequence) breakIterator.getText());
        if (!passagePageId.equals(pageId)) {
          continue;
        }
      }
      int end = off.endOffset();
      if (start < contentLength && end > contentLength) {
        continue;
      }
      // See if this term should be part of a new passage.
      if (start >= passage.getEndOffset()) {
        if (passage.getStartOffset() >= 0) {
          numTotal++;
        }
        passage = maybeAddPassage(passageQueue, passageScorer, passage, contentLength);
        // if we exceed limit, we are done
        if (start >= contentLength) {
          break;
        }
        // advance breakIterator
        passage.setStartOffset(Math.max(this.breakIterator.preceding(start + 1), 0));
        passage.setEndOffset(Math.min(this.breakIterator.following(end), contentLength));
      } else {
        passage.setEndOffset(Math.min(this.breakIterator.following(end), contentLength));
      }
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

  /**
   * Highlight passages from the document using the byte offsets in the payloads of the matching terms.
   *
   * Largely copied from {@link FieldHighlighter#highlightOffsetsEnums(OffsetsEnum)}, modified to load the byte offsets
   * from the term payloads.
   */
  protected Passage[] highlightByteOffsetsEnums(ByteOffsetsEnum off, int docId, String pageId) throws IOException {
    final int contentLength = this.breakIterator.getText().getEndIndex();
    if (!off.nextPosition()) {
      return new Passage[0];
    }
    // If we're filtering by a page identifier, we want *all* hits on that page
    int queueSize = pageId != null ? 4096 : maxPassages;
    if (queueSize  <= 0) {
      queueSize = 512;
    }
    queueSize = Math.min(512, queueSize);

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
    int numTotal = 0;
    do {
      int offset = off.byteOffset();
      this.breakIterator.getText().setIndex(offset);
      int end = offset;
      while (true) {
        char c = this.breakIterator.getText().next();
        end += Utf8.encodedLength(Character.toString(c));
        if (!Character.isLetter(c)) {
          break;
        }
      }
      if (offset < contentLength && end > contentLength) {
        continue;
      }
      if (pageId != null) {
        String passagePageId = ((OcrPassageFormatter) passageFormatter).determineStartPage(
            null, offset, (IterableCharSequence) breakIterator.getText());
        if (!passagePageId.equals(pageId)) {
          continue;
        }
      }
      // See if this term should be part of a new passage.
      if (offset >= passage.getEndOffset()) {
        if (passage.getStartOffset() >= 0) {
          numTotal++;
        }
        passage = maybeAddPassage(passageQueue, passageScorer, passage, contentLength);
        // if we exceed limit, we are done
        if (offset >= contentLength) {
          break;
        }
        // advance breakIterator
        passage.setStartOffset(Math.max(this.breakIterator.preceding(offset + 1), 0));
        passage.setEndOffset(Math.min(this.breakIterator.following(offset), contentLength));
      }
      // Add this term to the passage.
      BytesRef term = off.getTerm();// a reference; safe to refer to
      assert term != null;
      passage.addMatch(offset, end, term, off.freq());
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
