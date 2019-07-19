package de.digitalcollections.solrocr.util;

public class Region {
  public int start;
  public int end;
  public int startOffset = 0;

  public Region(int start, int end) {
    this.start = start;
    this.end = end;
  }

  public Region(int start, int end, int startOffset) {
    this(start, end);
    this.startOffset = startOffset;
  }
}
