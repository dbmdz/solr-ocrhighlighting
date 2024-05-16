package com.github.dbmdz.solrocr.formats.hocr;

import com.github.dbmdz.solrocr.iter.BaseBreakLocator;
import com.github.dbmdz.solrocr.iter.IterableCharSequence;
import com.google.common.collect.ImmutableList;
import java.util.List;

public class HocrClassBreakLocator extends BaseBreakLocator {
  private static final String BOM_ASCII = "ï»¿";
  private final List<String> breakClasses;
  private static final int overlap = 128;

  private final int blockSize;

  public HocrClassBreakLocator(IterableCharSequence text, String breakClass) {
    this(text, ImmutableList.of(breakClass), 32 * 1024);
  }

  public HocrClassBreakLocator(IterableCharSequence text, String breakClass, int blockSize) {
    this(text, ImmutableList.of(breakClass), blockSize);
  }

  public HocrClassBreakLocator(IterableCharSequence text, List<String> breakClasses, int blockSize) {
    super(text);
    this.breakClasses = breakClasses;
    this.blockSize = blockSize;
  }

  @Override
  protected int getFollowing(int offset) {
    int start = Math.min(offset + 1, this.text.getEndIndex());
    int end = Math.min(start + blockSize, this.text.getEndIndex());
    while (start < this.text.getEndIndex()) {
      String block = text.subSequence(start, end, true).toString();
      // Truncate block to last '>' to avoid splitting element openings across blocks
      int blockEnd = block.length();
      int lastTagClose = block.lastIndexOf('>');
      if (lastTagClose > 0
          && !isAllBlank(block, lastTagClose + 1, block.length())) {
        blockEnd = lastTagClose + 1;
        end = start + lastTagClose;
      }

      int match = findForwardMatch(block, 0, blockEnd);
      if (match >= 0) {
        return start + match;
      }

      start = end;
      if (start < this.text.getEndIndex()) {
        start -= overlap;
      }
      end = Math.min(start + blockSize, this.text.getEndIndex());
    }
    return this.text.getEndIndex();
  }

  @Override
  protected int getPreceding(int offset) {
    if (offset <= this.text.getBeginIndex()) {
      return this.text.getBeginIndex();
    }

    int end = Math.max(0, offset - 1);
    int start = Math.max(0, end - blockSize);
    while (start >= this.text.getBeginIndex()) {
      String block = text.subSequence(start, end, true).toString();
      int firstTagOpen = block.indexOf('<');
      int blockStart = 0;
      if (firstTagOpen > 0 && !isAllBlank(block, 0, firstTagOpen)) {
        // Limit all following searches to the beginning of the first tag in the block
        blockStart = firstTagOpen;
        start = start + firstTagOpen;
      }

      int match = findBackwardMatch(block, block.length(), blockStart);
      if (match >= 0) {
        return start - blockStart + match;
      }

      if (start == this.text.getBeginIndex()) {
        break;
      } else if (start > this.text.getBeginIndex()) {
        start += overlap;
      }
      end = start;
      start = Math.max(0, start - blockSize);
    }

    return this.text.getBeginIndex();
  }

  private int findForwardMatch(String text, int fromOffset, int toOffset) {
    for (String breakClass : this.breakClasses) {
      // Where to start looking from for a break in the next iteration
      int fromIdx = fromOffset;
      while (fromIdx < toOffset) {
        int i = text.indexOf(breakClass, fromIdx);
        if (i < 0 || i >= toOffset) {
          // Not found, try next class
          break;
        }
        int openIdx = text.lastIndexOf('<', i);
        int closeIdx = text.indexOf('>', i);
        assert closeIdx > openIdx : "startOffset and endOffset must be chosen to ensure a complete element";
        if (openIdx < fromOffset) {
          // Incomplete element, try next position
          fromIdx = closeIdx;
          continue;
        }
        int nextOpenIdx = text.indexOf('<', i);
        if (nextOpenIdx >= toOffset) {
          nextOpenIdx = -1;
        }
        if (nextOpenIdx > 0 && closeIdx > nextOpenIdx) {
          // Not inside an element tag, try next position
          fromIdx = i + breakClass.length();
          continue;
        }
        if (text.startsWith("meta", openIdx + 1)) {
          // Block specification in meta tag, not a real block, try next position
          fromIdx = closeIdx;
          continue;
        }
        // Found a match
        return openIdx;
      }
    }
    return -1;
  }

  private int findBackwardMatch(String text, int fromOffset, int toOffset) {
    if (fromOffset == 0 || fromOffset == toOffset) {
      return -1;
    }

    assert fromOffset
        > toOffset : "fromOffset must be greater than toOffset, we're looking backwards!";

    for (String breakClass : this.breakClasses) {
      // Look for the class in the block
      while (fromOffset > toOffset) {
        int i = optimizedLastIndexOf(text, breakClass, fromOffset);
        if (i < toOffset) {
          // Not found, try next class
          break;
        }
        int elemOpen = text.lastIndexOf('<', i);
        int previousClose = text.lastIndexOf('>', i);
        if (elemOpen < toOffset
            || previousClose > elemOpen
            || text.startsWith("meta", elemOpen + 1)) {
          // Class was not part of a tag or in the "meta" tag, keep looking
          fromOffset = Math.max(previousClose, elemOpen);
          continue;
        }
        return elemOpen;
      }
    }
    return -1;
  }

  private static boolean isAllBlank(String s, int from, int to) {
    if (s.startsWith(BOM_ASCII)) {
      from += BOM_ASCII.length();
    }
    for (int i = from; i < to; i++) {
      char c = s.charAt(i);
      if (!Character.isWhitespace(c)) {
        return false;
      }
    }
    return true;
  }
}
