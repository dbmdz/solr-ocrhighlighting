package com.github.dbmdz.solrocr.formats.hocr;

import com.github.dbmdz.solrocr.iter.BaseBreakLocator;
import com.github.dbmdz.solrocr.iter.IterableCharSequence;
import com.google.common.collect.ImmutableList;
import java.util.List;
import org.apache.commons.lang3.StringUtils;

public class HocrClassBreakLocator extends BaseBreakLocator {
  private final List<String> breakClasses;
  private final int overlap = 128;
  private final int blockSize;


  public HocrClassBreakLocator(IterableCharSequence text, String breakClass) {
    this(text, ImmutableList.of(breakClass), 64 * 1024);
  }

  public HocrClassBreakLocator(IterableCharSequence text, String breakClass, int blockSize) {
    this(text, ImmutableList.of(breakClass), blockSize);
  }

  public HocrClassBreakLocator(IterableCharSequence text, List<String> breakClasses) {
    this(text, breakClasses, 64 * 1024);
  }

  public HocrClassBreakLocator(
      IterableCharSequence text, List<String> breakClasses, int blockSize) {
    super(text);
    this.breakClasses = breakClasses;
    this.blockSize = blockSize;
  }

  @Override
  public int getFollowing(int offset) {
    int start = Math.min(offset + 1, this.text.getEndIndex());
    int end = Math.min(start + blockSize, this.text.getEndIndex());
    boolean needsMoreData = end == start + blockSize;
    while (start < this.text.getEndIndex()) {
      String block = text.subSequence(start, end, true).toString();
      // Truncate block to last '>' to avoid splitting element openings across blocks
      int blockEnd = block.length();
      int lastTagClose = block.lastIndexOf('>');
      if (lastTagClose > 0
          && !StringUtils.isAllBlank(block.subSequence(lastTagClose, block.length()))) {
        blockEnd = lastTagClose + 1;
        end = start + lastTagClose;
      }

      // In hOCR, there can be multiple options for expressing the same level in the block
      // hierarchy, so we need to check for all of them.
      int idx = blockEnd;
      int closeIdx = blockEnd - 1;
      outer:
      for (String breakClass : this.breakClasses) {
        int fromIdx = 0;
        while (true) {
          int i = block.indexOf(breakClass, fromIdx);
          if (i < 0 || i > blockEnd) {
            // Not found, try next class
            break;
          }
          closeIdx = block.indexOf('>', i);
          int elemOpen = block.lastIndexOf('<', i);
          if (elemOpen < 0) {
            // Incomplete element, try next position
            fromIdx = closeIdx;
            continue;
          }
          if (closeIdx > block.indexOf('<', i)) {
            // Not inside of an element tag, try next position
            fromIdx = i + breakClass.length();
            continue;
          }
          if (block.startsWith("meta", elemOpen + 1)) {
            // Block specification in meta tag, not a real block, try next position
            fromIdx = closeIdx;
            continue;
          }
          if (elemOpen < idx) {
            // Found match
            idx = elemOpen;
            needsMoreData = false;
            break outer;
          } else {
            // Try next class
            break;
          }
        }
      }
      if (!needsMoreData) {
        if (closeIdx < idx) {
          closeIdx = block.length();
        }
        this.text.setIndex(Math.min(start + closeIdx, this.text.getEndIndex()));
        return start + idx;
      }

      start = end;
      if (end < this.text.getEndIndex()) {
        end -= overlap;
      }
      end = Math.min(end + blockSize, this.text.getEndIndex());
    }
    return this.text.getEndIndex();
  }

  @Override
  protected int getPreceding(int offset) {
    int end = Math.max(0, offset - 1);
    int start = Math.max(0, end - blockSize);
    while (start >= this.text.getBeginIndex()) {
      String block = text.subSequence(start, end, true).toString();
      int firstTagOpen = block.indexOf('<');
      int blockStart = 0;
      if (firstTagOpen > 0 && !StringUtils.isBlank(block.subSequence(0, firstTagOpen))) {
        // Limit all following searches to the beginning of the first tag in the block
        blockStart = firstTagOpen;
      }
      int idx = -1;
      for (String breakClass : this.breakClasses) {
        // Look for the class in the block
        int fromIdx = block.length();
        while (true) {
          int i = optimizedLastIndexOf(block, breakClass, fromIdx);
          if (i < blockStart) {
            // Not found, try next class
            break;
          }
          int elemOpen = block.lastIndexOf('<', i);
          int previousClose = block.lastIndexOf('>', i);
          if (elemOpen < blockStart
              || previousClose > elemOpen
              || block.startsWith("meta", elemOpen + 1)) {
            // Class was not part of a tag or in the "meta" tag, keep looking
            fromIdx = Math.max(previousClose, elemOpen);
            continue;
          }
          if (elemOpen > idx) {
            idx = elemOpen;
          }
          break;
        }
      }

      if (idx >= blockStart) {
        return start + idx;
      }

      if (start == 0) {
        break;
      }
      end = start + overlap;
      start = Math.max(0, start - blockSize);
    }
    return this.text.getBeginIndex();
  }
}
