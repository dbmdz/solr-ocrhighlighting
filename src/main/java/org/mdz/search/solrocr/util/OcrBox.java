package org.mdz.search.solrocr.util;

public class OcrBox<T extends Number> {
  public final T x;
  public final T y;
  public final T width;
  public final T height;


  public OcrBox(T x, T y, T width, T height) {
    this.x = x;
    this.y = y;
    this.width = width;
    this.height = height;
  }
}
