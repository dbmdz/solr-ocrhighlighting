package com.github.dbmdz.solrocr.iter;

import com.github.dbmdz.solrocr.reader.SourceReader;
import com.github.dbmdz.solrocr.reader.SourceReader.Section;

/** A {@link BreakLocator} that splits an XML-like document on a specific opening or closing tag. */
public class TagBreakLocator extends BaseBreakLocator {
  private final String breakTag;

  public TagBreakLocator(SourceReader reader, String tagName) {
    this(reader, tagName, false);
  }

  public TagBreakLocator(SourceReader reader, String tagName, boolean closing) {
    super(reader);
    if (closing) {
      this.breakTag = ("</" + tagName + ">");
    } else {
      this.breakTag = ("<" + tagName);
    }
  }

  @Override
  protected int getFollowing(int offset) {
    String overlapHead = null;
    int globalStart = Math.min(offset + 1, this.text.length());
    while (globalStart < this.text.length()) {
      Section section = this.text.getAsciiSection(globalStart);
      String block = section.text;
      int blockStart = globalStart - section.start;

      if (overlapHead != null) {
        int firstTagClose = block.indexOf('>');
        int overlapStart = globalStart - overlapHead.length();
        String overlap = overlapHead.concat(block.substring(0, firstTagClose + 1));
        int overlapMatch = overlap.indexOf(breakTag);
        if (overlapMatch >= 0) {
          return overlapStart + overlapMatch;
        }
        blockStart = firstTagClose + 1;
        overlapHead = null;
      }

      // Truncate block to last '>' and keep the rest for the next iteration if needed
      int blockEnd = block.length();
      int lastTagClose = block.lastIndexOf('>');
      if (lastTagClose > 0 && !isAllBlank(block, lastTagClose + 1, blockEnd)) {
        String overlap = block.substring(lastTagClose + 1, blockEnd);
        if (overlap.indexOf('<') >= 0) {
          // Overlap has the start of a tag, carry over to next iteration
          overlapHead = overlap;
        }
        blockEnd = lastTagClose + 1;
      }

      int idx = block.indexOf(breakTag, blockStart);
      if (idx >= 0 && idx < blockEnd) {
        return section.start + idx;
      }

      globalStart = section.end;
    }
    return this.text.length();
  }

  @Override
  protected int getPreceding(int offset) {
    String overlapTail = null;
    int globalEnd = offset;

    while (globalEnd > 0) {
      Section section = this.text.getAsciiSection(globalEnd);
      String block = section.text;
      int blockEnd = globalEnd - section.start;

      if (overlapTail != null) {
        int lastTagOpen = block.lastIndexOf('<');
        String overlapHead = block.substring(lastTagOpen);
        String overlap = overlapHead.concat(overlapTail);
        int overlapMatch = optimizedLastIndexOf(overlap, breakTag, overlap.length());
        if (overlapMatch >= 0) {
          return section.start + lastTagOpen + overlapMatch;
        }
        blockEnd = lastTagOpen;
        overlapTail = null;
      }

      int blockStart = 0;
      int firstTagOpen = block.indexOf('<');
      if (firstTagOpen > 0 && firstTagOpen < blockEnd && !isAllBlank(block, 0, firstTagOpen)) {
        String overlap = block.substring(blockStart, firstTagOpen);
        if (overlap.indexOf('>') >= 0) {
          overlapTail = overlap;
        }
        blockStart = firstTagOpen;
      }

      int match = optimizedLastIndexOf(block, breakTag, blockEnd);
      if (match >= blockStart) {
        return section.start + match;
      }

      globalEnd = section.start - 1;
    }

    return 0;
  }
}
