package com.github.dbmdz.solrocr.lucene.filters;

import java.io.IOException;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;

/**
 * A buffered UTF-8 reader that can be repositioned and restricted to a byte range.
 *
 * <p>Used by {@link ExternalUtf8ContentFilter} during indexing to read whole OCR files or byte
 * regions specified by a {@link com.github.dbmdz.solrocr.model.SourcePointer}. Whole-file pointers
 * and regions starting at XML tags are aligned to UTF-8 character boundaries.
 *
 * <p>If a requested region ends in the middle of a multibyte UTF8 sequence, the region's end
 * becomes U+FFFD. Other malformed input, including a region starting on a continuation byte, is
 * rejected. With valid UTF-8 source files, these decoding errors can only result from malformed
 * source pointers, for example using character indices as byte offsets, not from ordinary read
 * chunking. Invalid UTF-8 bytes in the source files themselves are also rejected.
 */
public class ByteSeekableReader extends Reader {
  /** Underlying source channel; its position includes buffered read-ahead. */
  private final SeekableByteChannel channel;

  /** Strict UTF-8 decoder, reused across reads and reset when repositioning to a new region. */
  private final CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder();

  /**
   * Buffered source bytes, including incomplete UTF-8 prefixes retained across refills. Allocated
   * lazily and reused across regions; kept in read mode between decodes and compacted for refills.
   *
   * <p>The buffer starts at the size of the shortest region seen so far, grows to at most 16 KiB
   * and is never shrunk, so repositioning a reader does not allocate: the worst case is one 16 KiB
   * buffer per reader, i.e. per in-flight OCR document field, not per region.
   */
  private ByteBuffer bytes;

  /**
   * Two-code-unit scratch buffer for reads with one output slot remaining, allowing the decoder to
   * emit a complete surrogate pair before its halves are returned separately.
   */
  private final char[] singleChars = new char[2];

  /**
   * Cached view of the destination array used by {@link #decode(char[], int, int)}, either the
   * caller's buffer or {@link #singleChars}. Replaced only when the destination array changes.
   */
  private CharBuffer output;

  /**
   * Bytes still allowed to be read from the channel before the region ends, excluding bytes already
   * buffered. Initialized to {@link Long#MAX_VALUE} for reads without an explicit bound.
   */
  private long bytesRemaining = Long.MAX_VALUE;

  /**
   * Whether refilling has encountered the region end or channel EOF. Buffered bytes and a leftover
   * character may still need to be returned.
   */
  private boolean eof;

  /** Whether {@link #leftover} contains a decoded UTF-16 code unit awaiting delivery. */
  private boolean haveLeftover;

  /**
   * Second UTF-16 code unit decoded into {@link #singleChars} when only one caller-buffer slot
   * remained. Saved for the next read; can be an ordinary character, a low surrogate, or a boundary
   * replacement. Meaningful only while {@link #haveLeftover} is true.
   */
  private char leftover;

  /**
   * Source byte length of the truncated UTF-8 prefix represented by {@link #leftover}, or zero if
   * it is not a boundary-generated replacement. Saved until delivery; meaningful only while {@link
   * #haveLeftover} is true.
   */
  private int leftoverReplacementSourceByteLength;

  /**
   * Source byte length (1–3) of the truncated UTF-8 prefix represented by the final U+FFFD of the
   * last decode, or zero if there is no boundary-generated replacement. This is the source prefix's
   * length, not U+FFFD's three-byte UTF-8 encoding length.
   *
   * <p>{@link #decode} always sets it, so it never carries a value across decodes. {@link #read}
   * overrides it only when it returns nothing but a leftover code unit, whose own width is kept in
   * {@link #leftoverReplacementSourceByteLength}.
   */
  private int replacementSourceByteLength;

  /**
   * Whether the most recent read is known to be ASCII-only, allowing the filter to advance byte and
   * character offsets equally without scanning the returned characters.
   */
  private boolean asciiOnly;

  public ByteSeekableReader(SeekableByteChannel channel) {
    this.channel = channel;
  }

  /**
   * Decode up to {@code len} characters from the underlying byte channel into {@code cbuf}.
   *
   * <p>At most one code unit is ever carried over between calls; it is returned at the start of the
   * next read. After the call, {@link #replacementSourceByteLength()} describes the final returned
   * character and {@link #asciiOnly()} says whether the returned characters are all ASCII.
   *
   * @param cbuf destination buffer
   * @param off offset in {@code cbuf} at which to start writing
   * @param len maximum number of characters to write
   * @return number of characters written, or -1 if the region is exhausted
   */
  @Override
  public int read(char[] cbuf, int off, int len) throws IOException {
    if (!channel.isOpen()) {
      throw new IOException("Stream closed");
    }
    if (off < 0 || len < 0 || off > cbuf.length - len) {
      throw new IndexOutOfBoundsException();
    }
    replacementSourceByteLength = 0;
    asciiOnly = false;
    if (len == 0) {
      return 0;
    }
    if (bytes == null) {
      resetBuffer();
    }
    int copiedLeftoverChars = 0;
    // Width of the leftover code unit, applied only if this read returns nothing else.
    int leftoverWidth = 0;

    // Return left over char from a previous decode first
    if (haveLeftover) {
      cbuf[off++] = leftover;
      leftoverWidth = leftoverReplacementSourceByteLength;
      haveLeftover = false;
      copiedLeftoverChars = 1;
      if (--len == 0) {
        replacementSourceByteLength = leftoverWidth;
        asciiOnly = leftover < 0x80;
        return copiedLeftoverChars;
      }
    }

    // For single character reads we need to read into a 2-char scratch buffer so we can handle
    // the case where the channel is on a multi-char sequence so we can save the second part for
    // a later read.
    if (len == 1) {
      int count = decode(singleChars, 0, 2);
      asciiOnly =
          (count == 0 || singleChars[0] < 0x80) && (copiedLeftoverChars == 0 || leftover < 0x80);
      if (count > 0) {
        cbuf[off] = singleChars[0];
        if (count == 2) {
          leftover = singleChars[1];
          leftoverReplacementSourceByteLength = replacementSourceByteLength;
          replacementSourceByteLength = 0;
          haveLeftover = true;
        }
        return copiedLeftoverChars + 1;
      }
      replacementSourceByteLength = leftoverWidth;
      return copiedLeftoverChars > 0 ? copiedLeftoverChars : -1;
    }
    int count = decode(cbuf, off, len);
    if (copiedLeftoverChars > 0 && leftover >= 0x80) {
      asciiOnly = false;
    }
    if (count > 0) {
      return copiedLeftoverChars + count;
    }
    replacementSourceByteLength = leftoverWidth;
    return copiedLeftoverChars > 0 ? copiedLeftoverChars : -1;
  }

  /** Source byte length represented by the last read's final boundary replacement, or zero. */
  int replacementSourceByteLength() {
    return replacementSourceByteLength;
  }

  /** Whether the last read is known to be ASCII-only. */
  boolean asciiOnly() {
    return asciiOnly;
  }

  private int decode(char[] cbuf, int off, int len) throws IOException {
    replacementSourceByteLength = 0;
    // Decode straight into the caller's array, without copies.
    if (output == null || output.array() != cbuf) {
      output = CharBuffer.wrap(cbuf);
    }
    output.clear();
    output.position(off);
    output.limit(off + len);
    int decodedBytes = 0;
    while (true) {
      // First, use buffered bytes from previous reads
      int before = bytes.position();
      CoderResult result = decoder.decode(bytes, output, false);
      decodedBytes += bytes.position() - before;
      if (result.isError()) {
        result.throwException();
      }
      if (result.isOverflow() || !output.hasRemaining()) {
        break;
      }
      if (eof) {
        if (bytes.hasRemaining()) {
          // The decoder can defer rejecting ED A0..BF until a third byte arrives, even though
          // these prefixes can only encode surrogates, which are not valid Unicode scalar values.
          if (bytes.remaining() == 2
              && bytes.get(bytes.position()) == (byte) 0xED
              && (bytes.get(bytes.position() + 1) & 0xE0) == 0xA0) {
            throw new MalformedInputException(2);
          }
          // The remaining valid but incomplete UTF-8 prefix has a known source width, unlike
          // the three-byte UTF-8 encoding of the replacement character.
          replacementSourceByteLength = bytes.remaining();
          bytes.position(bytes.limit());
          output.put('\uFFFD');
        }
        break;
      }
      bytes.compact();
      if (bytesRemaining <= 0) {
        eof = true;
      } else {
        bytes.limit(bytes.position() + (int) Math.min(bytes.remaining(), bytesRemaining));
        int count = channel.read(bytes);
        if (count == 0) {
          // A FileChannel never does this while the buffer has room, and treating it as a no-op
          // would spin this loop forever, so fail loudly instead.
          throw new IOException("Underlying byte channel returned zero bytes");
        }
        if (count < 0) {
          eof = true;
        } else {
          bytesRemaining -= count;
        }
      }
      bytes.flip();
    }
    int count = output.position() - off;
    // Valid UTF-8 code points need at least as many bytes as UTF-16 characters, with equality
    // only for ASCII. The decoder emits whole code points here, so no character scan is needed.
    asciiOnly = replacementSourceByteLength == 0 && decodedBytes == count;
    return count;
  }

  /** Reposition to {@code [newPosition, limit)}; no bytes beyond the limit are read. */
  public void position(int newPosition, long limit) throws IOException {
    channel.position(newPosition);
    bytesRemaining = limit - newPosition;
    resetBuffer();
    haveLeftover = false;
    eof = false;
    replacementSourceByteLength = 0;
    asciiOnly = false;
    decoder.reset();
  }

  private void resetBuffer() {
    // Larger reads amortize FileChannel calls, but short ranges need only a small buffer.
    // Keep it across region changes, growing when necessary instead of reallocating per seek.
    int capacity = (int) Math.min(16384L, Math.max(4L, bytesRemaining));
    if (bytes == null || bytes.capacity() < capacity) {
      bytes = ByteBuffer.allocate(capacity);
    }
    bytes.clear();
    bytes.limit(0);
  }

  @Override
  public void close() throws IOException {
    channel.close();
    output = null;
  }
}
