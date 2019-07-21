package de.digitalcollections.solrocr.lucene.filters;

import de.digitalcollections.solrocr.util.SourcePointer;
import de.digitalcollections.solrocr.util.Utf8;
import java.io.IOException;
import java.io.Reader;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import org.apache.lucene.analysis.charfilter.BaseCharFilter;

public class ExternalUtf8ContentFilter extends BaseCharFilter {
  /**
   * The cumulative offset difference between the input (bytes) and the output (chars)
   * at the current position.
   *
   * current actual byte offset in input = currentOutOffset + cumulative
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
  private Queue<SourcePointer.Region> remainingRegions;
  private SourcePointer.Region currentRegion;

  public ExternalUtf8ContentFilter(Reader input, List<SourcePointer.Region> regions) throws IOException {
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
   * Read <tt>len</tt> <tt>char</tt>s into <tt>cbuf</tt>, starting from character index <tt>off</tt> relative to
   * the beginning of <tt>cbuf</tt> and return the number of <tt>char</tt>s read.
   **/
  @Override
  public int read(char[] cbuf, int off, int len) throws IOException {
    if (currentOffset == currentRegion.end) {
      return -1;
    }
    int numCharsRead = 0;
    while (len - numCharsRead > 0) {
      int charsRemainingInRegion = currentRegion.end - currentOffset;
      int charsToRead = len - numCharsRead;
      if (charsToRead > charsRemainingInRegion) {
        charsToRead = charsRemainingInRegion;
      }
      int read = this.input.read(cbuf, off, charsToRead);
      if (read < 0) {
        break;
      }
      correctOffsets(cbuf, off, charsToRead);
      numCharsRead += read;
      off += read;
      if (currentOffset == currentRegion.end) {
        if (remainingRegions.isEmpty()) {
          break;
        }
        currentRegion = remainingRegions.remove();
        int diff = currentRegion.startOffset - currentRegion.start - cumulative;
        if (diff > 0) {
          this.addOffCorrectMap(currentRegion.start, diff);
        }
        this.input.skip(this.currentRegion.start - this.currentOffset);
        this.currentOffset = currentRegion.start;
      }
    }
    return numCharsRead > 0 ? numCharsRead : -1;
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
      int increment = Utf8.encodedLength(cp) - 1;
      if (increment > 0) {
        cumulative += increment;
        nextIsOffset = true;
      }
    }
  }
}
