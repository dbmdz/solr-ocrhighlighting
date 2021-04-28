package de.digitalcollections.solrocr.iter;

import de.digitalcollections.solrocr.model.SourcePointer;
import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileChannel.MapMode;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * ATTENTION: This breaks the semantics of {@link java.text.CharacterIterator} and {@link
 * java.lang.CharSequence} since all indices are byte offsets into the underlying file,
 * <strong>not</strong> character indices. All methods that don't operate on indices should work as
 * expected.
 *
 * <p>Please note that this means that this type will only work with {@link java.text.BreakIterator}
 * types that don't mess with the index themselves.
 */
public class FileBytesCharIterator implements IterableCharSequence, AutoCloseable {
  private final byte[] copyBuf = new byte[128 * 1024];
  private final Path filePath; // For copy-constructor
  private final FileChannel chan;
  private final MappedByteBuffer buf;
  private final int numBytes;
  private final SourcePointer ptr;
  private final Charset charset;

  private int current;

  public FileBytesCharIterator(Path path, SourcePointer ptr) throws IOException {
    this(path, StandardCharsets.UTF_8, ptr);
  }

  public FileBytesCharIterator(Path path, Charset charset, SourcePointer ptr) throws IOException {
    this.ptr = ptr;
    this.charset = charset;
    this.filePath = path;
    chan = (FileChannel) Files.newByteChannel(path, StandardOpenOption.READ);
    this.numBytes = (int) chan.size();
    this.buf = chan.map(MapMode.READ_ONLY, 0, chan.size());
    if (this.charset == StandardCharsets.UTF_8) {
      byte[] b = new byte[4];
      buf.get(b);
      int[] validationBuf = new int[4];
      for (int i = 0; i < b.length; i++) {
        validationBuf[i] = b[i] & 0xFF;
      }
      // TODO: This is a pretty spotty heuristic, maybe there's something in the stdlib?
      if (!(validationBuf[0] == 0xEF && validationBuf[1] == 0xBB && validationBuf[2] == 0xBF)
          && ((validationBuf[0] >> 3) != 0b11110)
          && ((validationBuf[0] >> 4) != 0b1110)
          && ((validationBuf[0] >> 5) != 0b110)
          && ((validationBuf[0] >> 7) != 0)) {
        throw new IllegalArgumentException("File is not UTF-8 encoded");
      }
    }
  }

  public FileBytesCharIterator(FileBytesCharIterator other) throws IOException {
    this(other.filePath, other.charset, other.ptr);
    this.current = other.current;
  }

  @Override
  public int length() {
    return numBytes;
  }

  /** Move offset to the left until we're on an UTF8 starting byte * */
  private int adjustOffset(int b, int offset) {
    while ((b >> 6) == 0b10) {
      offset -= 1;
      b = this.buf.get(offset) & 0xFF;
    }
    return offset;
  }

  private int adjustOffset(int offset) {
    if (offset == numBytes) {
      return offset;
    }
    int b = this.buf.get(offset) & 0xFF;
    return adjustOffset(b, offset);
  }

  /**
   * Get ASCII character at the given byte offset.
   *
   * <p>Note that for performance reason this will simply return `?` if the byte at the given
   * position is not ASCII. This is done for a 25% performance boost while highlighting, with the
   * reasoning that the `charAt` method is only used by the `BreakIterator` implementations to find
   * OCR blocks. Every format supported by this plugin uses element names and attribute names that
   * are pure ASCII, so we're not missing out on anything relevant, as long as the user doesn't put
   * non-ASCII characters into attribute values.
   */
  @Override
  public char charAt(int offset) {
    int b = buf.get(offset) & 0xFF; // bytes are signed in Java....
    if (b < 0x80) {
      // Optimization: It's just ASCII, so simply cast to a char
      return (char) b;
    } else {
      // Dirty dirty dirty speed hack, see method docstring.
      return '?';
    }
  }

  @Override
  public CharSequence subSequence(int start, int end) {
    if (start < 0 || end < 0 || end > this.numBytes || end < start) {
      throw new IndexOutOfBoundsException();
    }
    if (charset == StandardCharsets.UTF_8) {
      start = adjustOffset(start);
      end = adjustOffset(end);
    }
    byte[] buf = new byte[end - start];
    this.buf.position(start);
    this.buf.get(buf);
    return new String(buf, charset);
  }

  public CharSequence subSequence(int start, int end, boolean forceAscii) {
    if (!forceAscii) {
      return subSequence(start, end);
    }
    if (start < 0 || end < 0 || end > this.numBytes || end < start) {
      throw new IndexOutOfBoundsException();
    }
    int copyLen = end - start;
    this.buf.position(start);
    this.buf.get(copyBuf, 0, end - start);

    // Faster pure-ASCII decoding, just treat everything as ASCII, a good chunk faster than
    // `new String(buf, StandardCharsets.US_ASCII)`, which has a few sanity checks.
    // Ignore the deprecation warning, the drawbacks of this constructor don't concern us
    // in this case, since we don't care about misinterpreted codepoints.
    // Bonus: With the String compaction available in JDK >= 9, this should be *significantly*
    //        faster than the constructor with an explicit charset.
    return new String(copyBuf, 0, 0, copyLen);
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
    if (this.current == this.numBytes) {
      return DONE;
    }
    return this.charAt(current);
  }

  @Override
  public char next() {
    char c = this.current();
    int inc = 1;
    if (Character.isHighSurrogate(c) || c > '\u07FF') {
      inc = 3;
    } else if (c > '\u007F') {
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

  @Override
  public String getIdentifier() {
    return this.filePath.toAbsolutePath().toString();
  }

  @Override
  public OffsetType getOffsetType() {
    return OffsetType.BYTES;
  }

  @Override
  public Charset getCharset() {
    return this.charset;
  }

  @Override
  public SourcePointer getPointer() {
    return ptr;
  }

  @Override
  public void close() throws IOException {
    chan.close();
  }
}
