package org.mdz.search.solrocr.util;

import java.io.IOException;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileChannel.MapMode;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public class FileCharIterator implements IterableCharSequence {
  private final Path filePath;  // For copy-constructor
  private final MappedByteBuffer buf;
  private final Charset charset;
  private final int startOffset;  // 0 if no BOM, otherwise 1
  private final long numChars;
  private int position;

  public FileCharIterator(Path path) throws IOException {
    this.filePath = path;
    FileChannel channel = (FileChannel) Files.newByteChannel(path, StandardOpenOption.READ);
    this.buf = channel.map(MapMode.READ_ONLY, 0, channel.size());
    byte[] bomBuf = new byte[2];
    this.buf.get(bomBuf);
    // Heuristic for determining the byte order: First check the BOM, and if that is not present check if the first
    // or second byte is zero. This will fail if the file starts with higher-valued code points, but in this case we
    // just ask the user to add a BOM to the file.
    if (bomBuf[0] == (byte) 0xFE && bomBuf[1] == (byte) 0xFF) {
      this.buf.order(ByteOrder.BIG_ENDIAN);
      this.charset = StandardCharsets.UTF_16BE;
      this.startOffset = 1;
    } else if (bomBuf[0] == (byte) 0xFF && bomBuf[1] == (byte) 0xFE) {
      this.buf.order(ByteOrder.LITTLE_ENDIAN);
      this.charset = StandardCharsets.UTF_16LE;
      this.startOffset = 1;
    } else if (bomBuf[0] == (byte) 0x00) {
      this.buf.order(ByteOrder.BIG_ENDIAN);
      this.charset = StandardCharsets.UTF_16BE;
      this.startOffset = 0;
    } else if (bomBuf[1] == (byte) 0x00) {
      this.buf.order(ByteOrder.LITTLE_ENDIAN);
      this.charset = StandardCharsets.UTF_16LE;
      this.startOffset = 0;
    } else {
      throw new IOException(String.format(
          "Could not determine UTF-16 byte order for %s, please add a BOM.", path));
    }
    this.numChars = channel.size() / 2 - this.startOffset;
    this.position = 0;
  }

  private FileCharIterator(FileCharIterator other) throws IOException {
    this(other.filePath);
    this.position = other.position;
  }

  @Override
  public int length() {
    return (int) this.numChars;
  }

  @Override
  public char charAt(int offset) {
    if (offset < 0 || offset >= this.numChars) {
      throw new IndexOutOfBoundsException();
    }
    return this.buf.getChar((this.startOffset + offset) * 2);
  }

  @Override
  public CharSequence subSequence(int start, int end) {
    if (start < 0 || end < 0 || end > this.numChars || end < start) {
      throw new IndexOutOfBoundsException();
    }
    byte[] buf = new byte[(end - start) * 2];
    this.buf.position((this.startOffset + start) * 2);
    this.buf.get(buf);
    return new String(buf, charset);
  }

  @Override
  public char first() {
    this.position = getBeginIndex();
    return this.current();
  }

  @Override
  public char last() {
    this.position = Math.max(0, getEndIndex() - 1);
    return this.current();
  }

  @Override
  public char current() {
    if (this.position == this.numChars) {
      return DONE;
    } else {
      return this.charAt(this.position);
    }
  }

  @Override
  public char next() {
    this.position = Math.min(this.position + 1, (int) this.numChars);
    if (this.position == this.numChars) {
      return DONE;
    }
    return this.current();
  }

  @Override
  public char previous() {
    this.position = Math.max(0, this.position - 1);
    if (this.position == 0) {
      return DONE;
    }
    return this.current();
  }

  @Override
  public char setIndex(int offset) {
    this.position = offset;
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
    return (int) this.numChars;
  }

  @Override
  public int getIndex() {
    return this.position;
  }

  @Override
  public Object clone() {
    try {
      return new FileCharIterator(this);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }
}
