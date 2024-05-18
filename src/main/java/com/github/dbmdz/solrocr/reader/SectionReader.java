package com.github.dbmdz.solrocr.reader;

import com.github.dbmdz.solrocr.iter.IterableCharSequence;
import java.util.Arrays;

public class SectionReader {
  public static class Section {
    public final int start;
    public final int end;
    public final String text;

    public Section(int start, int end, String text) {
      /* Start byte offset of the section, inclusive */
      this.start = start;
      /* End byte offset of the section, exclusive */
      this.end = end;
      /* ASCII text of the section */
      this.text = text;
    }
  }

  private final IterableCharSequence input;
  private final int sectionSize;
  private final int maxCacheEntries;
  private final Section[] cache;
  private final long[] cacheLastUsedTimestamps;
  private int cacheEntries = 0;

  public SectionReader(IterableCharSequence input) {
    this(input, 8 * 1024, 8);
  }

  public SectionReader(IterableCharSequence input, int sectionSize, int maxCacheEntries) {
    this.input = input;
    this.sectionSize = sectionSize;
    this.maxCacheEntries = maxCacheEntries;
    int numSections = (int) Math.ceil((double) input.length() / (double) sectionSize);
    this.cache = new Section[numSections];
    this.cacheLastUsedTimestamps = new long[numSections];
    Arrays.fill(cacheLastUsedTimestamps, -1L);
  }

  private void purgeLeastRecentlyUsed() {
    if (cacheEntries < maxCacheEntries) {
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
    cacheEntries--;
  }

  public Section getSection(int offset) {
    int sectionIndex = offset / sectionSize;
    if (cache[sectionIndex] != null) {
      cacheLastUsedTimestamps[sectionIndex] = System.nanoTime();
      return cache[sectionIndex];
    }
    int start = sectionIndex * sectionSize;
    int end = Math.min(start + sectionSize, input.length());
    Section section = new Section(start, end, input.subSequence(start, end, true).toString());
    if (cacheEntries > maxCacheEntries) {
      purgeLeastRecentlyUsed();
    }
    cache[sectionIndex] = section;
    cacheLastUsedTimestamps[sectionIndex] = System.nanoTime();
    cacheEntries++;
    return section;
  }

  public int getSectionSize() {
    return sectionSize;
  }

  public int length() {
    return input.length();
  }

  public IterableCharSequence getInput() {
    return input;
  }
}
