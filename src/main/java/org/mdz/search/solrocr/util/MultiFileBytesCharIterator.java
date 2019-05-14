package org.mdz.search.solrocr.util;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;

public class MultiFileBytesCharIterator implements IterableCharSequence {
  private final List<Path> paths;
  private final TreeMap<Integer, Path> offsetMap;
  private final Map<Path, Integer> pathToOffset;
  private final Charset charset;
  private final int numBytes;
  private int current;

  private final LoadingCache<Path, FileBytesCharIterator> subiters = CacheBuilder.newBuilder()
      .maximumSize(1000)
      .build(new CacheLoader<Path, FileBytesCharIterator>() {
        @Override
        public FileBytesCharIterator load(Path p) throws Exception {
          return new FileBytesCharIterator(p, charset);
        }
      });

  public MultiFileBytesCharIterator(List<Path> filePaths, Charset charset) throws IOException {
    this.paths = filePaths;
    this.charset = charset;
    this.offsetMap = new TreeMap<>();
    this.pathToOffset = new HashMap<>();
    int offset = 0;
    for (Path path : filePaths) {
      offsetMap.put(offset, path);
      pathToOffset.put(path, offset);
      offset += Files.size(path);
    }
    this.numBytes = offset;
  }

  public MultiFileBytesCharIterator(MultiFileBytesCharIterator other) throws IOException {
    this(other.paths, other.charset);
    this.current = other.current;
  }

  private IterableCharSequence getCharSeq(int offset) {
    try {
      Path path = offsetMap.floorEntry(offset).getValue();
      return subiters.get(path);
    } catch (ExecutionException e) {
      throw new RuntimeException(e);
    }
  }

  private int adjustOffset(int offset) {
    return offset - offsetMap.floorKey(offset);
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
  public int length() {
    return numBytes;
  }

  @Override
  public char charAt(int offset) {
    if (offset < 0 || offset >= this.numBytes) {
      throw new IndexOutOfBoundsException();
    }
    IterableCharSequence seq = getCharSeq(offset);
    int adjustedOffset = adjustOffset(offset);
    return seq.charAt(adjustedOffset);
  }

  @Override
  public CharSequence subSequence(int start, int end) {
    if (start < 0 || end < 0 || end > this.numBytes || end < start) {
      throw new IndexOutOfBoundsException();
    }
    if (offsetMap.floorKey(start).equals(offsetMap.floorKey(end))) {
      // Easy mode, start and end are in the same file
      IterableCharSequence seq = getCharSeq(start);
      return seq.subSequence(adjustOffset(start), adjustOffset(end));
    } else {
      IterableCharSequence seq = getCharSeq(start);
      int adjustedStart = adjustOffset(start);
      StringBuilder sb = new StringBuilder(seq.subSequence(adjustedStart, seq.length()));
      seq = getCharSeq(end);
      int adjustedEnd = adjustOffset(end);
      sb.append(seq.subSequence(0, adjustedEnd));
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
      return new MultiFileBytesCharIterator(this);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }
}
