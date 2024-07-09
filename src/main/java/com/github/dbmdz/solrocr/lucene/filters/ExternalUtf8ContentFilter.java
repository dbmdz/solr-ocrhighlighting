package com.github.dbmdz.solrocr.lucene.filters;

import com.github.dbmdz.solrocr.model.SourcePointer;
import com.github.dbmdz.solrocr.model.SourcePointer.Region;
import com.github.dbmdz.solrocr.util.SourceAwareReader;
import com.github.dbmdz.solrocr.util.Utf8;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.nio.CharBuffer;
import java.nio.channels.SeekableByteChannel;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import org.apache.lucene.analysis.charfilter.BaseCharFilter;

public class ExternalUtf8ContentFilter extends BaseCharFilter implements SourceAwareReader {

  /**
   * The cumulative offset difference between the input (bytes) and the output (chars) at the
   * current position.
   *
   * <p>Used to calculate the <strong>byte</strong> offset in the input, given a
   * <strong>char</strong> offset from the output of this filter.
   *
   * <pre>
   * currentInputByteOffset = currentOutCharOffset + cumulativeOffsetDifference
   * </pre>
   */
  private int cumulativeOffsetDifference;

  /**
   * The current <strong>byte</strong> offset in the <strong>full</strong> input, i.e. the
   * concatenated content of all files in the source pointer.
   */
  private int currentInByteOffset;

  /**
   * The current <strong>char</strong> offset in the output, i.e. the concatenated and decoded
   * content of all regions in the source pointer.
   */
  private int currentOutCharOffset;

  /** Source pointer of this reader, used for debugging and error reporting. */
  private final String pointer;

  /** Whether the last seen character had more than 1 byte for a char */
  private boolean lastCharHadMultipleBytes = false;

  private final Queue<SourcePointer.Region> remainingRegions;
  private SourcePointer.Region currentRegion;

  public ExternalUtf8ContentFilter(
      SeekableByteChannel channel, List<SourcePointer.Region> regions, String pointer)
      throws IOException {
    // We need to be able to reposition the underlying reader, so we use our own implementation
    // based on a SeekableByteChannel.
    super(new ByteSeekableReader(channel));
    if (regions == null || regions.isEmpty()) {
      regions = ImmutableList.of(new Region(0, (int) channel.size()));
    }
    this.pointer = pointer;
    this.currentOutCharOffset = 0;
    this.currentInByteOffset = 0;
    this.cumulativeOffsetDifference = 0;
    this.remainingRegions = new LinkedList<>(regions);
    currentRegion = remainingRegions.remove();
    if (currentRegion.start > 0) {
      this.addOffCorrectMap(currentOutCharOffset, currentRegion.start);
      this.cumulativeOffsetDifference += currentRegion.start;
      this.currentInByteOffset = currentRegion.start;
      ((ByteSeekableReader) this.input).position(currentInByteOffset);
    }
  }

  /**
   * Read <tt>requestedCharLen</tt> <tt>char</tt>s into <tt>outputBuffer</tt>, starting from
   * character index <tt>outputCharOffset</tt> relative to the beginning of <tt>outputBuffer</tt>
   * and return the number of <tt>char</tt>s read.
   *
   * <p>Keeps track of the current byte offset in the input and the current char offset in the
   * output.
   */
  @Override
  public int read(char[] outputBuffer, int outputCharOffset, int requestedCharLen)
      throws IOException {
    if (currentInByteOffset == currentRegion.end) {
      return -1;
    }

    int numCharsRead = 0;
    while (requestedCharLen - numCharsRead > 0) {
      int bytesRemainingInRegion = currentRegion.end - currentInByteOffset;
      int charsToRead = requestedCharLen - numCharsRead;
      if (charsToRead > bytesRemainingInRegion) {
        charsToRead = bytesRemainingInRegion;
      }

      int charsRead = this.input.read(outputBuffer, outputCharOffset, charsToRead);
      if (charsRead < 0) {
        break;
      }
      while (Utf8.encodedLength(CharBuffer.wrap(outputBuffer, outputCharOffset, charsRead))
          > bytesRemainingInRegion) {
        charsRead--;
      }
      correctOffsets(outputBuffer, outputCharOffset, charsRead);
      numCharsRead += charsRead;
      outputCharOffset += charsRead;

      if (currentInByteOffset == currentRegion.end) {
        if (remainingRegions.isEmpty()) {
          break;
        }
        currentRegion = remainingRegions.remove();

        cumulativeOffsetDifference = currentRegion.start - currentOutCharOffset;
        this.addOffCorrectMap(currentOutCharOffset, cumulativeOffsetDifference);
        if (this.currentRegion.start > this.currentInByteOffset) {
          this.currentInByteOffset = currentRegion.start;
        }
        ((ByteSeekableReader) this.input).position(this.currentInByteOffset);
      }
    }
    return numCharsRead > 0 ? numCharsRead : -1;
  }

  /**
   * Updates the current input and output offsets based on the characters read from the input.
   *
   * @param decodedChars Buffer of characters that were read from the input
   * @param bufOffset Offset in decodedChars, where the stored characters start
   * @param numChars Number of characters stored in decodedChars
   */
  private void correctOffsets(char[] decodedChars, int bufOffset, int numChars) {
    for (int i = bufOffset; i < bufOffset + numChars; ) {
      if (lastCharHadMultipleBytes) {
        this.addOffCorrectMap(currentOutCharOffset, cumulativeOffsetDifference);
        lastCharHadMultipleBytes = false;
      }
      int cp = Character.codePointAt(decodedChars, i);
      int encodedLen = Utf8.encodedLength(cp);
      int charLen = Character.charCount(cp);
      i += charLen;
      currentOutCharOffset += charLen;
      currentInByteOffset += encodedLen;
      if (encodedLen > 1) {
        cumulativeOffsetDifference += (encodedLen - 1);
        lastCharHadMultipleBytes = true;
      }
    }
  }

  @Override
  public Optional<String> getSource() {
    return Optional.of(this.pointer);
  }
}
