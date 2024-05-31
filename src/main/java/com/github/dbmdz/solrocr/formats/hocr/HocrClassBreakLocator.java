package com.github.dbmdz.solrocr.formats.hocr;

import com.github.dbmdz.solrocr.breaklocator.BaseBreakLocator;
import com.github.dbmdz.solrocr.reader.SourceReader;
import com.github.dbmdz.solrocr.reader.SourceReader.Section;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.util.List;

public class HocrClassBreakLocator extends BaseBreakLocator {

  private final List<String> breakClasses;

  public HocrClassBreakLocator(SourceReader reader, String breakClass) {
    this(reader, ImmutableList.of(breakClass));
  }

  public HocrClassBreakLocator(SourceReader reader, List<String> breakClasses) {
    super(reader);
    this.breakClasses = breakClasses;
  }

  @Override
  protected int getFollowing(int offset) throws IOException {
    int globalStart = Math.min(offset + 1, this.text.length());
    String overlapHead = null;
    // Read the source section-wise  to cut down on String allocations and improve the chance of
    // cache hits in the reader
    while (globalStart < this.text.length()) {
      Section section = this.text.getAsciiSection(globalStart);
      String block = section.text;
      int blockStart = globalStart - section.start;

      // There was an overlap from the previous block, combine with the current block up until
      // the first tag close and see if there's a match
      if (overlapHead != null) {
        int firstTagClose = block.indexOf('>');
        int overlapStart = globalStart - overlapHead.length();
        String overlap = overlapHead.concat(block.substring(0, firstTagClose + 1));
        int overlapMatch = findForwardMatch(overlap, 0, overlap.length());
        if (overlapMatch >= 0) {
          return overlapStart + overlapMatch;
        }
        blockStart = firstTagClose + 1;
        overlapHead = null;
      }

      // Truncate block to last '>' and keep the rest for the next iteration if needed
      int blockEnd = block.length();
      int lastTagOpen = block.lastIndexOf('<');
      if (lastTagOpen < blockStart) {
        lastTagOpen = -1;
      }
      int lastTagClose = block.lastIndexOf('>');
      if (lastTagClose < blockStart) {
        lastTagClose = -1;
      }
      boolean partialTag =
          (lastTagOpen >= 0 && lastTagClose < 0)
              || (lastTagClose >= 0 && lastTagClose < lastTagOpen);
      if (partialTag) {
        overlapHead = block.substring(lastTagOpen, blockEnd);
        blockEnd = lastTagOpen + 1;
      }

      int match = findForwardMatch(block, blockStart, blockEnd);
      if (match >= 0) {
        return section.start + match;
      }

      globalStart = section.end;
    }
    return this.text.length();
  }

  @Override
  protected int getPreceding(int offset) throws IOException {
    if (offset <= 0) {
      return 0;
    }

    // Read the source section-wise  to cut down on String allocations and improve the chance of
    // cache hits in the reader
    String overlapTail = null;
    int globalEnd = Math.max(0, offset - 1);
    while (globalEnd > 0) {
      Section section = this.text.getAsciiSection(globalEnd);

      String block = section.text;
      int blockEnd = globalEnd - section.start;

      // There was an overlap from the previous block, combine with the current block up until
      // the first tag open and see if there's a match
      if (overlapTail != null) {
        int lastTagOpen = block.lastIndexOf('<', blockEnd);
        String overlapHead = block.substring(lastTagOpen);
        int overlapStartOffset = globalEnd - overlapHead.length() + 1;
        String overlap = overlapHead.concat(overlapTail);
        int overlapMatch = findBackwardMatch(overlap, overlap.length(), 0);
        if (overlapMatch >= 0) {
          return overlapStartOffset + overlapMatch;
        }
        blockEnd = lastTagOpen;
        overlapTail = null;
      }

      int blockStart = 0;
      int firstTagOpen = block.indexOf('<');
      if (firstTagOpen > blockEnd) {
        firstTagOpen = -1;
      }
      int firstTagClose = block.indexOf('>');
      if (firstTagClose > blockEnd) {
        firstTagClose = -1;
      }
      boolean partialTag =
          (firstTagOpen < 0 && firstTagClose >= 0)
              || (firstTagClose >= 0 && firstTagClose < firstTagOpen);
      if (partialTag) {
        // Section starts on a partial tag, store it for the next iteration
        blockStart = firstTagClose + 1;
        overlapTail = block.substring(0, firstTagClose + 1);
      }

      int match = findBackwardMatch(block, blockEnd, blockStart);
      if (match >= 0) {
        return section.start + match;
      }

      globalEnd = section.start - 1;
    }

    return 0;
  }

  /** Find a match for one of the break classes in the given String, seeking forward. */
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
        if (openIdx < fromIdx && closeIdx < 0) {
          // No match
          break;
        }
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

  /** Find a match for one of the break classes in the given String, seeking backwards. */
  private int findBackwardMatch(String text, int fromOffset, int toOffset) {
    if (fromOffset == 0 || fromOffset == toOffset) {
      return -1;
    }

    assert fromOffset > toOffset
        : "fromOffset must be greater than toOffset, we're looking backwards!";

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
}
