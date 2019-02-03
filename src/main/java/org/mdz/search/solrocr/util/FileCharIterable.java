package org.mdz.search.solrocr.util;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.CharacterIterator;

public class FileCharIterable implements CharSequence, CharacterIterator {
  private final Path filePath;  // For copy-constructor
  private final RandomAccessFile file;
  private final long numChars;
  private int position;

  public FileCharIterable(Path path) throws IOException {
    this.filePath = path;
    this.file = new RandomAccessFile(path.toFile(), "r");
    this.numChars = Files.size(path) / 2;
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
      this.file.seek(offset * 2);
      byte[] buf = new byte[2];
      this.file.read(buf);
      return new String(buf, StandardCharsets.UTF_16BE).charAt(0);
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
      byte[] buf = new byte[(end - start) * 2];
      this.file.seek(start * 2);
      this.file.read(buf);
      this.file.seek(position);
      return new String(buf, StandardCharsets.UTF_16BE);
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
