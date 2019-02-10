package org.mdz.search.solrocr.util;

import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileChannel.MapMode;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** ATTENTION: This breaks the semantics of {@link java.text.CharacterIterator} and {@link java.lang.CharSequence}
 *             since all indices are byte offsets into the underlying file, <strong>not</strong> character indices.
 *             All methods that don't operate on indices should work as expected.
 *
 *             Please note that this means that this type will only work with {@link java.text.BreakIterator} types
 *             that don't mess with the index themselves.
 */
public class FileBytesCharIterator implements IterableCharSequence {
  private final Path filePath;  // For copy-constructor
  private final MappedByteBuffer buf;
  private final int startOffset;
  private final int numBytes;
  private final CharsetDecoder decoder;

  private int current;

  public FileBytesCharIterator(Path path) throws IOException {
    this.filePath = path;
    FileChannel channel = (FileChannel) Files.newByteChannel(path, StandardOpenOption.READ);
    this.numBytes = (int) channel.size();
    this.buf = channel.map(MapMode.READ_ONLY, 0, channel.size());
    byte[] b = new byte[4];
    buf.get(b);
    int[]  validationBuf = new int[4];
    for (int i=0; i < b.length; i++) {
      validationBuf[i] = b[i] & 0xFF;
    }
    // TODO: This is a pretty spotty heuristic, maybe there's something in the stdlib?
    if (!(validationBuf[0] == 0xEF && validationBuf[1] == 0xBB && validationBuf[2] == 0xBF)
        && ((validationBuf[0] >> 3) != 0b11110 )
        && ((validationBuf[0] >> 4) != 0b1110 )
        && ((validationBuf[0] >> 5) != 0b110)
        && ((validationBuf[0] >> 7) != 0)) {
      throw new IllegalArgumentException("File is not UTF-8 encoded");
    }
    this.decoder = StandardCharsets.UTF_8.newDecoder();
    if (validationBuf[0] == 0xEF) {
      this.startOffset = 3;
    } else {
      this.startOffset = 0;
    }
  }

  public FileBytesCharIterator(FileBytesCharIterator other) throws IOException {
    this(other.filePath);
    this.current = other.current;
  }

  @Override
  public int length() {
    return numBytes;
  }

  private int adjustOffset(int offset) {
    int b = this.buf.get(offset) & 0xFF;
    while ((b >> 6) == 0b10) {
      offset -= 1;
      b = this.buf.get(offset) & 0xFF;
    }
    return offset;
  }

  /** Get character at the given byte offset.
   *
   * Note that this will seek back if we're landing inside of a UTF-8 codepoint.
   * Also, if the UTF-8 string results in an multi-char UTF-16 codepoint, this will return the first char if we're on
   * the first to third byte of the UTF-8 sequence and the second char if we're on the fourth byte. (?? TODO correct ??)
   */
  @Override
  public char charAt(int offset) {
    if (offset < 0 || offset >= this.numBytes) {
      throw new IndexOutOfBoundsException();
    }
    int originalOffset = offset;
    offset = adjustOffset(offset);
    int b = buf.get(offset) & 0xFF;  // bytes are signed in Java....
    int bytesToRead;
    if ((b >> 7) == 0) {
      bytesToRead = 1;
    } else if ((b >> 5) == 0b110) {
      bytesToRead = 2;
    } else if ((b >> 4) == 0b1110) {
      bytesToRead = 3;
    } else if ((b >> 3) == 0b11110) {
      bytesToRead = 4;
    } else {
      throw new IllegalArgumentException("Invalid UTF8?");
    }
    byte[] buf = new byte[bytesToRead];
    this.buf.position(offset);
    this.buf.get(buf);
    String s = new String(buf, StandardCharsets.UTF_8);
    if (s.length() == 1 || ((originalOffset - offset) < 3)) {
      return s.charAt(0);
    } else {
      return s.charAt(1);
    }
  }

  @Override
  public CharSequence subSequence(int start, int end) {
    if (start < 0 || end < 0 || end > this.numBytes || end < start) {
      throw new IndexOutOfBoundsException();
    }
    start = adjustOffset(start);
    end = adjustOffset(end);
    byte[] buf = new byte[end - start];
    this.buf.position(start);
    this.buf.get(buf);
    return new String(buf, StandardCharsets.UTF_8);
  }

  @Override
  public char first() {
    this.current = this.getBeginIndex();
    return this.current();
  }

  @Override
  public char last() {
    this.current = this.getEndIndex() - 1;
    return this.current();
  }

  @Override
  public char current() {
    return this.charAt(current);
  }

  @Override
  public char next() {
    char c = this.current();
    int inc = 1;
    if (Character.isHighSurrogate(c) || c > '\u07FF') {
      inc = 3;
    }  else if (c > '\u007F') {
      inc = 2;
    }
    this.current = Math.min(this.current + inc, this.numBytes);
    if (this.current == this.numBytes) {
      return DONE;
    }
    return this.current();
  }

  @Override
  public char previous() {
    if (this.current > 0) {
      char c = this.current();
      int dec = 1;
      if (Character.isLowSurrogate(c) || c > '\u07FF') {
        dec = 3;
      } else if (c > '\u007F') {
        dec = 2;
      }
      this.current = Math.max(this.current - dec, 0);
      return this.current();
    } else {
      return DONE;
    }
  }

  @Override
  public char setIndex(int offset) {
    this.current = offset;
    try {
      return this.current();
    } catch (IndexOutOfBoundsException e) {
      throw new IllegalArgumentException(e);
    }
  }

  @Override
  public int getBeginIndex() {
    return 0;
  }

  @Override
  public int getEndIndex() {
    return numBytes;
  }

  @Override
  public int getIndex() {
    return current;
  }

  @Override
  public Object clone() {
    try {
      return new FileBytesCharIterator(this);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }
}
