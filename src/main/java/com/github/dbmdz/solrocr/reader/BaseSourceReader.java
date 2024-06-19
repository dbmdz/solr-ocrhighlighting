package com.github.dbmdz.solrocr.reader;

import com.github.dbmdz.solrocr.model.SourcePointer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Base class that provides caching and section reading for source readers.
 *
 * <p>Implementers should inherit from this and simply implement the {@link
 * BaseSourceReader#readBytes(byte[], int, int, int)} method.
 */
public abstract class BaseSourceReader implements SourceReader {
  private static final int UNUSED = -1;

  protected final SourcePointer pointer;
  protected final int sectionSize;
  private final byte[] copyBuf;
  protected final int maxCacheEntries;

  /**
   * Array with a slot for every possible section in the source, of which only {@link
   * BaseSourceReader#maxCacheEntries} slots will ever be non-null
   */
  CachedSection[] cache;
  /**
   * Array of length {@link BaseSourceReader#maxCacheEntries} with the indexes of the sections that
   * are currently cached
   */
  int[] cachedSectionIdxes;

  int cacheSlotsUsed = 0;

  private enum AdjustDirection {
    LEFT,
    RIGHT
  }

  static final class CachedSection {
    public final Section section;
    public long lastUsedTimestampNs;

    private CachedSection(Section section) {
      this.section = section;
      this.lastUsedTimestampNs = System.nanoTime();
    }
  }

  public BaseSourceReader(SourcePointer pointer, int sectionSize, int maxCacheEntries) {
    this.pointer = pointer;
    this.sectionSize = sectionSize;
    this.copyBuf = new byte[sectionSize];
    this.maxCacheEntries = maxCacheEntries;
  }

  @Override
  public abstract int length() throws IOException;

  @Override
  public abstract void close() throws IOException;

  @Override
  public abstract String getIdentifier();

  @Override
  public SourcePointer getPointer() {
    return pointer;
  }

  /**
   * Initialize data structures for section cache.
   *
   * <p>Gotta do this outside of the constructor because we need to know the length, which is only
   * available after the constructor has run
   *
   * @throws IOException
   */
  private void initializeCache() throws IOException {
    // We trade off some memory for a simpler implementation by using a fixed-size cache
    // with `null` entries for unused slots plus a timestamp array to track LRU
    // The memory impact is not too bad, even for small section sizes like 1KiB, the
    // cache will only occupy around 40KiB for a 10MiB file
    int numSections = (int) Math.ceil((double) this.length() / sectionSize);
    this.cache = new CachedSection[numSections];
    this.cachedSectionIdxes = new int[maxCacheEntries];
    Arrays.fill(cachedSectionIdxes, UNUSED);
  }

  /** If the cache is full, remove the least recently used section */
  private void purgeLeastRecentlyUsed() {
    if (this.cache.length == 0 || cacheSlotsUsed < maxCacheEntries) {
      return;
    }

    long oldestTimestamp = Long.MAX_VALUE;
    int oldestIndex = -1;
    int idxOfOldestIndex = -1;
    for (int i = 0; i < cachedSectionIdxes.length; i++) {
      int sectionIdx = cachedSectionIdxes[i];
      if (sectionIdx < 0) {
        continue;
      }
      if (cache[sectionIdx].lastUsedTimestampNs < oldestTimestamp) {
        oldestTimestamp = cache[sectionIdx].lastUsedTimestampNs;
        oldestIndex = sectionIdx;
        idxOfOldestIndex = i;
      }
    }
    cache[oldestIndex] = null;
    cachedSectionIdxes[idxOfOldestIndex] = UNUSED;
    cacheSlotsUsed--;
  }

  @Override
  public String readAsciiString(int start, int len) throws IOException {
    if (start < 0) {
      throw new IllegalArgumentException("start must be >= 0");
    }
    if (start + len > this.length()) {
      len = this.length() - start;
    }
    StringBuilder sb = new StringBuilder(len);
    int numRead = 0;
    while (numRead < len) {
      Section section = getAsciiSection(start + numRead);
      int sectionStart = (start + numRead) - section.start;
      int sectionEnd = Math.min(sectionStart + (len - numRead), section.end - section.start);
      sb.append(section.text, sectionStart, sectionEnd);
      numRead += (sectionEnd - sectionStart);
    }
    return sb.toString();
  }

  @Override
  public String readUtf8String(int start, int byteLen) throws IOException {
    // NOTE: This is currently not on any of the hot paths, so we don't bother with caching
    if (start < 0) {
      throw new IllegalArgumentException("start must be >= 0");
    }
    if (start + byteLen > this.length()) {
      byteLen = this.length() - start;
    }
    byte[] data = new byte[byteLen];
    int numRead = 0;
    while (numRead < byteLen) {
      numRead += this.readBytes(data, numRead, start + numRead, byteLen - numRead);
    }
    int dataStart = adjustOffset(0, data, AdjustDirection.RIGHT);
    int dataEnd = adjustOffset(data.length - 1, data, AdjustDirection.LEFT);
    return new String(data, dataStart, dataEnd - dataStart + 1, StandardCharsets.UTF_8);
  }

  /**
   * Move offset if we're on a partital UTF8 multi-byte sequence so that we start at a valid UTF8
   * sequence
   */
  private static int adjustOffset(int offset, byte[] buf, AdjustDirection direction) {
    int b = buf[offset] & 0xFF;
    if (direction == AdjustDirection.RIGHT) {
      while ((b >> 6) == 0b10) {
        offset += 1;
        if (offset >= buf.length) {
          return -1;
        }
        b = buf[offset] & 0xFF;
      }
    } else {
      int hiBits = (b >> 6);
      boolean isMultiByte = hiBits == 0b11 || hiBits == 0b10;
      if (!isMultiByte) {
        return offset;
      }
      int shift = 0;
      while (offset - shift >= 0) {
        shift++;
        b = buf[offset - shift] & 0xFF;
        hiBits = b >> 6;
        if (hiBits == 0b11) {
          if (b >> 5 == 0b110 && shift == 1) {
            // Complete 2-byte sequence
            return offset;
          } else if (b >> 4 == 0b1110 && shift == 2) {
            // Complete 3-byte sequence
            return offset;
          } else if (b >> 3 == 0b11110 && shift == 3) {
            // Complete 4-byte sequence
            return offset;
          } else {
            // Unfinished sequence
            return offset - shift - 1;
          }
        } else if (hiBits < 2) {
          // Non-multibyte character, adjust to herer
          return offset - shift;
        }
      }
    }
    return offset;
  }

  public Section getAsciiSection(int offset) throws IOException {
    if (offset < 0) {
      throw new IllegalArgumentException("offset must be >= 0");
    }
    if (offset >= this.length()) {
      throw new IllegalArgumentException("offset must be < length");
    }
    int sectionIndex = offset / sectionSize;
    if (cache == null) {
      initializeCache();
    }
    if (cache[sectionIndex] != null) {
      cache[sectionIndex].lastUsedTimestampNs = System.nanoTime();
      return cache[sectionIndex].section;
    }
    int startOffset = sectionIndex * sectionSize;
    int readLen = Math.min(sectionSize, this.length() - startOffset);
    int numRead = 0;
    while (numRead < readLen) {
      numRead += this.readBytes(copyBuf, numRead, startOffset + numRead, readLen - numRead);
    }
    // Construct a String without going through a decoder to save on CPU.
    // Given that the method has been deprecated since Java 1.1 and was never removed, I don't think
    // this is very risky 😅
    Section section =
        new Section(startOffset, startOffset + readLen, new String(copyBuf, 0, 0, readLen));
    if (cache.length > 0 && cacheSlotsUsed == maxCacheEntries) {
      purgeLeastRecentlyUsed();
    }
    if (cache.length > 0) {
      for (int i = 0; i < cachedSectionIdxes.length; i++) {
        if (cachedSectionIdxes[i] < 0) {
          cachedSectionIdxes[i] = sectionIndex;
          break;
        }
      }
      cache[sectionIndex] = new CachedSection(section);
      cacheSlotsUsed++;
    }

    return section;
  }
}
