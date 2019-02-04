package org.mdz.search.solrocr.util;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.CharacterIterator;

public class FileCharIterable implements CharSequence, CharacterIterator {

  private final Path filePath;  // For copy-constructor
  private final RandomAccessFile file;
  private final long numChars;
  private final Charset charset;
  private final boolean hasBom;
  private int position;

  public FileCharIterable(Path path) throws IOException {
    this.filePath = path;
    this.file = new RandomAccessFile(path.toFile(), "r");
    byte[] bomBuf = new byte[2];
    this.file.read(bomBuf);
    // Heuristic for determining the byte order: First check the BOM, and if that is not present check if the first
    // or second byte is zero. This will fail if the file starts with higher-valued code points, but in this case we
    // just ask the user to add a BOM to the file.
    if (bomBuf[0] == (byte) 0xFE && bomBuf[1] == (byte) 0xFF) {
      this.charset = StandardCharsets.UTF_16BE;
      this.hasBom = true;
    } else if (bomBuf[0] == (byte) 0xFF && bomBuf[1] == (byte) 0xFE) {
      this.charset = StandardCharsets.UTF_16LE;
      this.hasBom = true;
    } else if (bomBuf[0] == (byte) 0x00) {
      this.charset = StandardCharsets.UTF_16BE;
      this.hasBom = false;
    } else if (bomBuf[1] == (byte) 0x00) {
      this.charset = StandardCharsets.UTF_16LE;
      this.hasBom = false;
    } else {
      throw new IOException(String.format(
          "Could not determine UTF-16 byte order for %s, please add a BOM.", path));
    }
    this.numChars = (Files.size(path) - (hasBom ? 2 : 0)) / 2;
    this.position = 0;
  }

  private FileCharIterable(FileCharIterable other) throws IOException {
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
    try {
      if (hasBom) {
        offset += 1;
      }
      this.file.seek(offset * 2);
      byte[] buf = new byte[2];
      this.file.read(buf);
      return new String(buf, charset).charAt(0);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public CharSequence subSequence(int start, int end) {
    if (start < 0 || end < 0 || end >= this.numChars || end < start) {
      throw new IndexOutOfBoundsException();
    }
    try {
      if (hasBom) {
        start += 1;
        end += 1;
      }
      byte[] buf = new byte[(end - start) * 2];
      this.file.seek(start * 2);
      this.file.read(buf);
      this.file.seek(position);
      return new String(buf, charset);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public char first() {
    return this.charAt(0);
  }

  @Override
  public char last() {
    return this.charAt((int) (this.numChars - 1));
  }

  @Override
  public char current() {
    return this.charAt(this.position);
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
    return this.current();
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
    return 0;
  }

  @Override
  public Object clone() {
    try {
      return new FileCharIterable(this);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }
}
