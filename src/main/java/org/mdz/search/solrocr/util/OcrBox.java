package org.mdz.search.solrocr.util;

public class OcrBox {
  public final float ulx;
  public final float uly;
  public final float lrx;
  public final float lry;


  public OcrBox(float ulx, float uly, float lrx, float lry) {
    this.ulx = ulx;
    this.uly = uly;
    this.lrx = lrx;
    this.lry = lry;
  }
}
