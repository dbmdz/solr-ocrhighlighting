package org.mdz.search.solrocr.util;

public class OcrBox {
  public float ulx;
  public float uly;
  public float lrx;
  public float lry;


  public OcrBox(float ulx, float uly, float lrx, float lry) {
    this.ulx = ulx;
    this.uly = uly;
    this.lrx = lrx;
    this.lry = lry;
  }
}
