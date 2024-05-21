package com.github.dbmdz.solrocr.reader;

import com.github.dbmdz.solrocr.model.SourcePointer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public abstract class BaseSourceReader implements SourceReader {

  protected final SourcePointer pointer;
  protected final int sectionSize;
  // cache slot → section
  private final Section[] cache;
  // cache slot → timestamp
  private final long[] cacheLastUsedTimestamps;
  // cache slot -> section idx
  private final int[] cachedSections;
  private int cacheSlotsUsed = 0;
  private final byte[] copyBuf;

  public BaseSourceReader(SourcePointer pointer, int sectionSize, int maxCacheEntries) {
    this.pointer = pointer;
    this.sectionSize = sectionSize;
    this.cache = new Section[maxCacheEntries];
    this.cacheLastUsedTimestamps = new long[maxCacheEntries];
    this.cachedSections = new int[maxCacheEntries];
    Arrays.fill(cacheLastUsedTimestamps, -1L);
    Arrays.fill(cachedSections, -1);
    this.copyBuf = new byte[sectionSize];
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

  private void purgeLeastRecentlyUsed() {
    if (this.cache.length == 0 || cacheSlotsUsed < this.cache.length) {
      return;
    }

    long oldestTimestamp = Long.MAX_VALUE;
    int oldestIndex = -1;
    for (int i = 0; i < cacheLastUsedTimestamps.length; i++) {
      if (cacheLastUsedTimestamps[i] < oldestTimestamp) {
        oldestTimestamp = cacheLastUsedTimestamps[i];
        oldestIndex = i;
      }
    }
    cache[oldestIndex] = null;
    cacheLastUsedTimestamps[oldestIndex] = -1L;
    cacheSlotsUsed--;
  }

  private int getCacheSlot(int sectionIndex) {
    for (int i = 0; i < cache.length; i++) {
      if (cachedSections[i] == sectionIndex) {
        return i;
      }
    }
    return -1;
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
    int cacheSlot = getCacheSlot(sectionIndex);
    if (cacheSlot >= 0) {
      cacheLastUsedTimestamps[cacheSlot] = System.nanoTime();
      return cache[cacheSlot];
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
      cacheSlot = getCacheSlot(-1);
      cache[cacheSlot] = section;
      cacheLastUsedTimestamps[cacheSlot] = System.nanoTime();
      cacheSlotsUsed++;
    }
    return section;
  }
}
