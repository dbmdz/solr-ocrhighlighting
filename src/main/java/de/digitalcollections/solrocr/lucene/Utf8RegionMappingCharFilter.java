package de.digitalcollections.solrocr.lucene;

import de.digitalcollections.solrocr.util.Region;
import java.io.IOException;
import java.io.Reader;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import org.apache.lucene.analysis.charfilter.BaseCharFilter;

public class Utf8RegionMappingCharFilter extends BaseCharFilter {
  /**
   * The cumulative offset difference between the input (bytes) and the output (chars)
   * at the current position.
   *
   * currentByteOffset = currentCharOffset + cumulative
   */
  private int cumulative;

  /**
   * The current <strong>char</strong> offset in the full file;
   */
  private int currentOffset;

  /**
   * The current <strong>char</strong> offset in the output.
   */
  private int currentOutOffset;

  private boolean nextIsOffset = false;
  private Queue<Region> remainingRegions;
  private Region currentRegion;

  public Utf8RegionMappingCharFilter(Reader input, List<Region> regions) throws IOException {
    super(input);
    this.currentOutOffset = 0;
    this.currentOffset = 0;
    this.cumulative = 0;
    this.remainingRegions = new LinkedList<>(regions);
    currentRegion = remainingRegions.remove();
    if (currentRegion.start > 0) {
      this.currentOffset = currentRegion.start;
      this.addOffCorrectMap(currentOutOffset, currentRegion.startOffset);
      this.cumulative += currentRegion.startOffset;
      this.input.skip(currentOffset);
    }
  }

  /**
   * Read {@param len} <tt>char</tt>s into {@param cbuf}, starting from character index {@param off} relative to
   * the beginning of {@param cbuf} and return the number of <tt>char</tt>s read.
   */
  @Override
  public int read(char[] cbuf, int off, int len) throws IOException {
    if (currentOffset == currentRegion.end) {
      return -1;
    }
    int numCharsRead = 0;
    while (len - numCharsRead > 0) {
      int charsRemainingInRegion = currentRegion.end - currentOffset;
      int charsToRead = len;
      if (charsToRead > charsRemainingInRegion) {
        charsToRead = charsRemainingInRegion;
      }
      int read = this.input.read(cbuf, off, charsToRead);
      correctOffsets(cbuf, off, charsToRead);
      numCharsRead += read;
      if (currentOffset == currentRegion.end) {
        if (remainingRegions.isEmpty()) {
          break;
        }
        currentRegion = remainingRegions.remove();
        int diff = currentRegion.startOffset - currentRegion.start - cumulative;
        if (diff > 0) {
          this.addOffCorrectMap(currentRegion.start, diff);
        }
        this.currentOffset = currentRegion.start;
        this.input.skip(this.currentOffset);
      }
    }
    return numCharsRead;
  }

  private void correctOffsets(char[] cbuf, int off, int len) {
    for (int i=off; i < off + len; i++) {
      if (nextIsOffset) {
        this.addOffCorrectMap(currentOutOffset, cumulative);
        nextIsOffset = false;
      }
      currentOffset += 1;
      currentOutOffset += 1;
      int cp = Character.codePointAt(cbuf, i);
      int increment = charUtf8Size(cp) - 1;
      if (increment > 0) {
        cumulative += increment;
        nextIsOffset = true;
      }
    }
  }

  private static int charUtf8Size(int cp) {
    if (cp < 0x80) {
      return 1;
    } else if (cp < 0x800) {
      return 2;
    } else if (cp < 0x10000 || cp >= 0x110000) {
      return 3;
    } else {
      return 4;
    }
  }

}
