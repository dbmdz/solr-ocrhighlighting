package com.github.dbmdz.solrocr.reader;

import com.github.dbmdz.solrocr.iter.IterableCharSequence;

public class SectionReaderFactory {
  private final int sectionSize;
  private final int maxCachedEntries;

  public SectionReaderFactory(int sectionSizeKib, int maxCacheSizeKib) {
    this.sectionSize = sectionSizeKib * 1024;
    this.maxCachedEntries = (int) Math.ceil((double) (maxCacheSizeKib * 1024) / sectionSize);
  }

  public SectionReader createReader(IterableCharSequence input) {
    return new SectionReader(input, sectionSize, maxCachedEntries);
  }
}
