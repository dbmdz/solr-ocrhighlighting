package com.github.dbmdz.solrocr.lucene.filters;

import com.github.dbmdz.solrocr.model.SourcePointer;
import com.github.dbmdz.solrocr.model.SourcePointer.Region;
import com.github.dbmdz.solrocr.util.SourceAwareReader;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
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
    validateRegions(regions, pointer);
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
    }
    ((ByteSeekableReader) this.input).position(currentInByteOffset, currentRegion.end);
  }

  /**
   * Reject regions that cannot describe a byte range.
   *
   * <p>Open-ended regions ({@code start:}) reach this class as {@code end == -1} and must be
   * resolved to the file length by the caller, which {@code ExternalUtf8ContentFilterFactory} does
   * before constructing the filter. Accepting them silently would decode nothing at all, since a
   * negative byte count reads as an exhausted region.
   */
  private static void validateRegions(List<Region> regions, String pointer) {
    for (Region region : regions) {
      if (region.start < 0 || region.end < 0) {
        throw new IllegalArgumentException(
            String.format(
                Locale.US,
                "Region %s in %s has a negative offset; open ends must be resolved to the file length"
                    + " before constructing the filter",
                region,
                pointer));
      }
      if (region.end < region.start) {
        throw new IllegalArgumentException(
            String.format(Locale.US, "Region %s in %s ends before it starts", region, pointer));
      }
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
    if (outputCharOffset < 0
        || requestedCharLen < 0
        || outputCharOffset > outputBuffer.length - requestedCharLen) {
      throw new IndexOutOfBoundsException();
    }
    if (requestedCharLen == 0) {
      return 0;
    }
    int numCharsRead = 0;
    ByteSeekableReader reader = (ByteSeekableReader) this.input;
    while (numCharsRead < requestedCharLen) {
      try {
        int charsRead =
            reader.read(outputBuffer, outputCharOffset, requestedCharLen - numCharsRead);
        if (charsRead < 0) {
          if (remainingRegions.isEmpty()) {
            break;
          }
          currentRegion = remainingRegions.remove();
          currentInByteOffset = Math.max(currentInByteOffset, currentRegion.start);
          cumulativeOffsetDifference = currentInByteOffset - currentOutCharOffset;
          addOffCorrectMap(currentOutCharOffset, cumulativeOffsetDifference);
          reader.position(currentInByteOffset, currentRegion.end);
          continue;
        }
        if (reader.asciiOnly()) {
          currentInByteOffset += charsRead;
          currentOutCharOffset += charsRead;
        } else {
          correctOffsets(
              outputBuffer, outputCharOffset, charsRead, reader.replacementSourceByteLength());
        }
        numCharsRead += charsRead;
        outputCharOffset += charsRead;
      } catch (IOException e) {
        throw new IOException(
            String.format(
                Locale.ROOT,
                "Failed to read region %s from '%s' near byte offset %d",
                currentRegion,
                pointer,
                currentInByteOffset),
            e);
      }
    }
    return numCharsRead > 0 ? numCharsRead : -1;
  }

  /** Update corrections, skipping ASCII runs whose byte and character lengths are equal. */
  private void correctOffsets(char[] chars, int offset, int length, int boundaryReplacementWidth) {
    assert boundaryReplacementWidth >= 0 && boundaryReplacementWidth <= 3
        : boundaryReplacementWidth;
    int byteOffset = currentInByteOffset;
    int charOffset = currentOutCharOffset;
    int end = offset + length;
    for (int i = offset; i < end; ) {
      int asciiStart = i;
      while (i < end && chars[i] < 0x80) {
        i++;
      }
      byteOffset += i - asciiStart;
      charOffset += i - asciiStart;
      if (i == end) {
        break;
      }
      char c = chars[i++];
      int width = sourceWidth(c);
      if (i == end && boundaryReplacementWidth > 0) {
        // A boundary replacement always ends a read: a truncated tail means the region ended, so
        // nothing can follow it. Its width is the truncating prefix's length, not U+FFFD's three.
        assert c == '\uFFFD' : c;
        width = boundaryReplacementWidth;
      }
      byteOffset += width;
      charOffset++;
      cumulativeOffsetDifference = byteOffset - charOffset;
      addOffCorrectMap(charOffset, cumulativeOffsetDifference);
    }
    currentInByteOffset = byteOffset;
    currentOutCharOffset = charOffset;
  }

  /**
   * Source byte width of one decoded character, from the decoded value alone.
   *
   * <p>A surrogate pair is billed together on its low surrogate: across small caller reads the high
   * surrogate can arrive alone, and the pair's four source bytes belong to the code point, not to
   * either half of it.
   */
  private static int sourceWidth(char c) {
    if (Character.isHighSurrogate(c)) {
      return 0;
    }
    if (Character.isLowSurrogate(c)) {
      return 4;
    }
    return c < 0x800 ? 2 : 3;
  }

  @Override
  public Optional<String> getSource() {
    return Optional.of(this.pointer);
  }
}
