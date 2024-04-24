package com.github.dbmdz.solrocr.iter;

import com.github.dbmdz.solrocr.model.SourcePointer;
import com.github.dbmdz.solrocr.util.Utf8;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MultiFileBytesCharIterator implements IterableCharSequence, AutoCloseable {
  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  private final List<Path> paths;
  private final TreeMap<Integer, Path> offsetMap;
  private final Charset charset;
  private final int numBytes;
  private final SourcePointer ptr;
  private int current;
  private final Map<Path, FileBytesCharIterator> subiters = new HashMap<>();

  public MultiFileBytesCharIterator(List<Path> filePaths, Charset charset, SourcePointer ptr)
      throws IOException {
    this.ptr = ptr;
    this.paths = filePaths;
    this.charset = charset;
    this.offsetMap = new TreeMap<>();
    int offset = 0;
    for (Path path : filePaths) {
      offsetMap.put(offset, path);
      offset += Files.size(path);
    }
    this.numBytes = offset;
  }

  public MultiFileBytesCharIterator(MultiFileBytesCharIterator other) throws IOException {
    this(other.paths, other.charset, other.ptr);
    this.current = other.current;
  }

  private IterableCharSequence getCharSeq(int offset) {
    Path path = offsetMap.floorEntry(offset).getValue();
    FileBytesCharIterator it = subiters.get(path);
    if (it == null) {
      try {
        it = new FileBytesCharIterator(path, charset, ptr);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
      subiters.put(path, it);
    }
    return it;
  }

  /** Given an absolute offset, return the relative offset in the file the offset resides in. */
  private int getFileRelativeOffset(int offset) {
    return offset - offsetMap.floorKey(offset);
  }

  @Override
  public String getIdentifier() {
    return String.format(
        Locale.US,
        "{%s}",
        this.paths.stream().map(Path::toString).collect(Collectors.joining(", ")));
  }

  @Override
  public OffsetType getOffsetType() {
    return OffsetType.BYTES;
  }

  @Override
  public Charset getCharset() {
    return charset;
  }

  @Override
  public SourcePointer getPointer() {
    return ptr;
  }

  @Override
  public int length() {
    return numBytes;
  }

  @Override
  public char charAt(int offset) {
    if (offset < 0 || offset >= this.numBytes) {
      throw new IndexOutOfBoundsException();
    }
    IterableCharSequence seq = getCharSeq(offset);
    int adjustedOffset = getFileRelativeOffset(offset);
    return seq.charAt(adjustedOffset);
  }

  @Override
  public CharSequence subSequence(int start, int end, boolean forceAscii) {
    if (start < 0 || end < 0 || end > this.numBytes || end < start) {
      throw new IndexOutOfBoundsException();
    }
    if (offsetMap.floorKey(start).equals(offsetMap.floorKey(end))) {
      // Easy mode, start and end are in the same file
      IterableCharSequence seq = getCharSeq(start);
      return seq.subSequence(getFileRelativeOffset(start), getFileRelativeOffset(end), forceAscii);
    } else {
      // Hard mode, start and end span multiple files
      final int targetLength = end - start;
      StringBuilder sb = new StringBuilder(targetLength);
      for (int charsRead = 0; charsRead < targetLength; ) {
        IterableCharSequence seq = getCharSeq(start + charsRead);
        int fileOffsetStart = getFileRelativeOffset(start + charsRead);
        int fileOffsetEnd = Math.min(seq.length(), fileOffsetStart + (targetLength - charsRead));

        sb.append(seq.subSequence(fileOffsetStart, fileOffsetEnd, forceAscii));
        charsRead += fileOffsetEnd - fileOffsetStart;
      }
      return sb.toString();
    }
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
    int inc = Utf8.encodedLength(Character.toString(this.current()));
    this.current = Math.min(this.current + inc, this.numBytes);
    if (this.current == this.numBytes) {
      return DONE;
    }
    return this.current();
  }

  @Override
  public char previous() {
    if (this.current > 0) {
      int dec = Utf8.encodedLength(Character.toString(this.current()));
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
      return new MultiFileBytesCharIterator(this);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public void close() {
    subiters.forEach(
        (p, it) -> {
          try {
            it.close();
          } catch (Exception e) {
            log.warn("Encountered error while closing sub-iterator for {}: {}", p, e.getMessage());
          }
        });
  }
}
