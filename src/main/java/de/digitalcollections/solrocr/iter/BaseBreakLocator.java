package de.digitalcollections.solrocr.iter;

import com.google.common.collect.Range;
import com.google.common.collect.RangeMap;
import com.google.common.collect.TreeRangeMap;
import java.text.BreakIterator;
import java.util.Map.Entry;

/** Simplified version of a {@link BreakIterator}, without most of the state (except for the text)
 *  and automated caching.
 */
@SuppressWarnings("UnstableApiUsage")
public abstract class BaseBreakLocator implements BreakLocator {
  private final RangeMap<Integer, Integer> forwardCache = TreeRangeMap.create();
  private final RangeMap<Integer, Integer> backwardCache = TreeRangeMap.create();
  protected final IterableCharSequence text;

  protected BaseBreakLocator(IterableCharSequence text) {
    this.text = text;
  }

  @Override
  public IterableCharSequence getText() {
    return text;
  }

  @Override
  public int following(int offset) {
    if (offset >= this.text.length()) {
      return DONE;
    }
    Integer cached = this.forwardCache.get(offset);
    if (cached != null) {
      return cached;
    }
    Entry<Range<Integer>, Integer> entry = this.backwardCache.getEntry(offset);
    int preceding;
    if (entry == null) {
      preceding = this.getPreceding(offset);
    } else {
      if (entry.getKey().upperEndpoint() == offset) {
        preceding = offset;
      } else {
        preceding = entry.getValue();
      }
    }
    /*
    Integer preceding = this.backwardCache.get(offset);
    if (preceding == null) {
      preceding = this.getPreceding(offset);
    }
    */
    int following = this.getFollowing(offset);
    if (following < 0) {
      following = this.text.length();
    }
    this.forwardCache.put(Range.closedOpen(preceding, following), following);
    this.backwardCache.put(Range.openClosed(preceding, following), preceding);
    return following;
  }

  @Override
  public int preceding(int offset) {
    if (offset <= 0) {
      return DONE;
    }
    Integer cached = this.backwardCache.get(offset);
    if (cached != null) {
      return cached;
    }
    Entry<Range<Integer>, Integer> entry = this.forwardCache.getEntry(offset);
    int following;
    if (entry == null) {
        following = this.getFollowing(offset);
    } else {
      if (entry.getKey().lowerEndpoint() == offset) {
        following = offset;
      } else {
        following = entry.getValue();
      }
    }
    int preceding = this.getPreceding(offset);
    this.backwardCache.put(Range.openClosed(Math.max(0, preceding), following), preceding);
    this.forwardCache.put(Range.closedOpen(Math.max(0, preceding), following), following);
    return preceding;
  }

  protected abstract int getPreceding(int offset);

  protected abstract int getFollowing(int offset);



}
