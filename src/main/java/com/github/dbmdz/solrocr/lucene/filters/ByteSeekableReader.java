package com.github.dbmdz.solrocr.lucene.filters;

import java.io.IOException;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.StandardCharsets;
import org.jspecify.annotations.NonNull;

/**
 * A buffered UTF-8 reader that can be repositioned and restricted to a byte range.
 *
 * <p>A valid UTF-8 prefix cut off by the range end becomes U+FFFD. Other malformed input, including
 * a range starting on a continuation byte, is rejected.
 */
public class ByteSeekableReader extends Reader {
  private final SeekableByteChannel channel;
  private final CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder();
  private ByteBuffer bytes;
  private final char[] singleChars = new char[2];
  private CharBuffer output;
  private long bytesRemaining = Long.MAX_VALUE;
  private boolean eof;
  private boolean haveLeftover;
  private char leftover;
  private int leftoverReplacementBytes;
  private int replacementBytes;
  private boolean asciiOnly;

  public ByteSeekableReader(SeekableByteChannel channel) {
    this.channel = channel;
  }

  @Override
  public int read(char @NonNull [] cbuf, int off, int len) throws IOException {
    if (!channel.isOpen()) {
      throw new IOException("Stream closed");
    }
    if (off < 0 || len < 0 || off > cbuf.length - len) {
      throw new IndexOutOfBoundsException();
    }
    replacementBytes = 0;
    asciiOnly = false;
    if (len == 0) {
      return 0;
    }
    if (bytes == null) {
      resetBuffer();
    }
    int copiedLeftoverChars = 0;
    if (haveLeftover) {
      cbuf[off++] = leftover;
      replacementBytes = leftoverReplacementBytes;
      haveLeftover = false;
      copiedLeftoverChars = 1;
      if (--len == 0) {
        return copiedLeftoverChars;
      }
    }
    if (len == 1) {
      // CharsetDecoder emits surrogate pairs together. Save the second UTF-16 character
      // when a caller only has room for one, including its boundary-replacement metadata.
      int count = decode(singleChars, 0, 2);
      asciiOnly = false; // This read may return only half of the decoded code point.
      if (count > 0) {
        cbuf[off] = singleChars[0];
        if (count == 2) {
          leftover = singleChars[1];
          leftoverReplacementBytes = replacementBytes;
          replacementBytes = 0;
          haveLeftover = true;
        }
        return copiedLeftoverChars + 1;
      }
      return copiedLeftoverChars > 0 ? copiedLeftoverChars : -1;
    }
    int count = decode(cbuf, off, len);
    if (copiedLeftoverChars > 0 && leftover >= 0x80) {
      // ASCII leftovers preserve the one-byte-per-character fast path. For other
      // leftovers, decode's byte count alone cannot determine their source width.
      asciiOnly = false;
    }
    // decode() returns zero at EOF; only a positive count means characters were produced.
    if (count > 0) {
      return copiedLeftoverChars + count;
    } else if (copiedLeftoverChars > 0) {
      return copiedLeftoverChars;
    } else {
      return -1;
    }
  }

  /** Source byte length of a truncated prefix replaced by the last character of the last read. */
  int replacementBytes() {
    return replacementBytes;
  }

  /** Whether the last read is known to be ASCII-only. */
  boolean asciiOnly() {
    return asciiOnly;
  }

  private int decode(char[] cbuf, int off, int len) throws IOException {
    // XML readers usually reuse their input array. Reuse the view too, rather than allocating
    // a CharBuffer on every small read. Decode straight into the caller's array, without copies.
    if (output == null || output.array() != cbuf) {
      output = CharBuffer.wrap(cbuf);
    }
    output.clear();
    output.position(off);
    output.limit(off + len);
    int decodedBytes = 0;
    while (true) {
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
          // Underflow leaves only a valid but incomplete UTF-8 prefix. Its source width is
          // known exactly, unlike the three-byte UTF-8 encoding of the replacement character.
          replacementBytes = bytes.remaining();
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
    asciiOnly = replacementBytes == 0 && decodedBytes == count;
    return count;
  }

  /** Return the current byte position in the underlying channel (including read-ahead). */
  public int position() throws IOException {
    return (int) channel.position();
  }

  /** Reposition the reader and reset its buffered state. */
  public void position(int newPosition) throws IOException {
    position(newPosition, Long.MAX_VALUE);
  }

  /** Reposition to {@code [newPosition, limit)}; no bytes beyond the limit are read. */
  public void position(int newPosition, long limit) throws IOException {
    channel.position(newPosition);
    bytesRemaining = limit - newPosition;
    resetBuffer();
    haveLeftover = false;
    eof = false;
    replacementBytes = 0;
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
