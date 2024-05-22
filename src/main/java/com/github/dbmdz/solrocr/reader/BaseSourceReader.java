package com.github.dbmdz.solrocr.reader;

import com.github.dbmdz.solrocr.model.SourcePointer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public abstract class BaseSourceReader implements SourceReader {

  protected final SourcePointer pointer;
  protected final int sectionSize;
  private final byte[] copyBuf;
  private final int maxCacheEntries;

  private CachedSection[] cache;
  private int[] cachedSectionIdxes;
  private int cacheSlotsUsed = 0;

  private static final class CachedSection {
    public final Section section;
    public long lastUsedTimestamp;

    private CachedSection(Section section) {
      this.section = section;
      this.lastUsedTimestamp = System.nanoTime();
    }
  }

  public BaseSourceReader(SourcePointer pointer, int sectionSize, int maxCacheEntries) {
    this.pointer = pointer;
    this.sectionSize = sectionSize;
    this.copyBuf = new byte[sectionSize];
    this.maxCacheEntries = maxCacheEntries;
  }

  protected abstract int readBytes(byte[] dst, int dstOffset, int start, int len);

  @Override
  public abstract int length();

  @Override
  public abstract void close();

  @Override
  public abstract String getIdentifier();

  @Override
  public SourcePointer getPointer() {
    return pointer;
  }

  private void initializeCache() {
    // Gotta do this outside of the constructor because we need to know the length,
    // which is only available after the constructor has run
    // We trade off some memory for a simpler implementation by using a fixed-size cache
    // with `null` entries for unused slots plus a timestamp array to track LRU
    // The memory impact is not too bad, even for small section sizes like 1KiB, the
    // cache will only occupy around 40KiB for a 10MiB file
    int numSections = (int) Math.ceil((double) this.length() / sectionSize);
    this.cache = new CachedSection[numSections];
    this.cachedSectionIdxes = new int[maxCacheEntries];
    Arrays.fill(cachedSectionIdxes, -1);
  }

  private void purgeLeastRecentlyUsed() {
    if (this.cache.length == 0 || cacheSlotsUsed < this.cache.length) {
      return;
    }

    long oldestTimestamp = Long.MAX_VALUE;
    int oldestIndex = -1;
    for (int sectionIdx : cachedSectionIdxes) {
      if (sectionIdx < 0) {
        continue;
      }
      if (cache[sectionIdx].lastUsedTimestamp < oldestTimestamp) {
        oldestTimestamp = cache[sectionIdx].lastUsedTimestamp;
        oldestIndex = sectionIdx;
      }
    }
    cache[oldestIndex] = null;
    cacheSlotsUsed--;
  }

  @Override
  public String readAsciiString(int start, int len) {
    if (start < 0) {
      throw new IllegalArgumentException("start must be >= 0");
    }
    if (start + len > this.length()) {
      len = this.length() - start;
    }
    StringBuilder sb = new StringBuilder();
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
  public String readUtf8String(int start, int byteLen) {
    // NOTE: This is currently not on any of the hot paths, so we don't bother with caching
    if (start < 0) {
      throw new IllegalArgumentException("start must be >= 0");
    }
    if (start + byteLen > this.length()) {
      byteLen = this.length() - start;
    }
    byte[] data = new byte[byteLen];
    this.readBytes(data, 0, start, byteLen);
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

  public Section getAsciiSection(int offset) {
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
      cache[sectionIndex].lastUsedTimestamp = System.nanoTime();
      return cache[sectionIndex].section;
    }
    int startOffset = sectionIndex * sectionSize;
    int readLen = Math.min(sectionSize, this.length() - startOffset);
    this.readBytes(copyBuf, 0, startOffset, readLen);
    Section section =
        new Section(startOffset, startOffset + sectionSize, new String(copyBuf, 0, 0, readLen));
    if (cache.length > 0 && cacheSlotsUsed == cache.length) {
      purgeLeastRecentlyUsed();
    }
    if (cache.length > 0) {
      cache[sectionIndex] = new CachedSection(section);
      cacheSlotsUsed++;
    }

    return section;
  }
}
