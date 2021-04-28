package de.digitalcollections.solrocr.iter;

import com.google.common.collect.Range;
import com.google.common.collect.RangeMap;
import com.google.common.collect.TreeRangeMap;
import java.text.BreakIterator;
import java.util.Map.Entry;

/**
 * Simplified version of a {@link BreakIterator}, without most of the state (except for the text)
 * and automated caching.
 */
@SuppressWarnings("UnstableApiUsage")
public abstract class BaseBreakLocator implements BreakLocator {
  private final RangeMap<Integer, Integer> forwardCache = TreeRangeMap.create();
  private final RangeMap<Integer, Integer> backwardCache = TreeRangeMap.create();
  protected final IterableCharSequence text;

  /**
   * An "optimized" version of {@link String#lastIndexOf(String, int)}.
   *
   * <p>This optimization is a bit counter-intuitive, since, on the surface, it uses the absolute
   * worst way to search for the last occurrence of a substring in a larger string, by starting from
   * the front of the string and working forward until there are no more matches.
   *
   * <p>So why does using this approach speed up highlighting hOCR by ~25%? Well, during hOCR break
   * locating, we need to look for multiple possible block type identifiers for each level in the
   * block hierarchy, i.e. we often run into the situation there is no match. These cases are the
   * absolute worst case for both {@link String#lastIndexOf(String, int)} and {@link
   * String#indexOf(String, int)}. So why is the latter faster? Well, in recent Hotspot versions, it
   * is is SIMD-accelerated via compiler intrinsics. And the speed-up we get from these worst cases
   * is significant enough to completely make up for the slightly worse performance in more ideal
   * cases (~25% slower).
   */
  protected static int optimizedLastIndexOf(String haystack, String needle, int fromIdx) {
    int from = -1;
    int idx;
    while ((idx = haystack.indexOf(needle, from + 1)) >= 0 && idx < fromIdx) {
      from = idx;
    }
    return from;
  }

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
