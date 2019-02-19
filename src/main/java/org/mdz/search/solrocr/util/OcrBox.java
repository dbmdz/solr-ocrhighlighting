package org.mdz.search.solrocr.util;

public class OcrBox {
  public String text;
  public float ulx;
  public float uly;
  public float lrx;
  public float lry;


  public OcrBox(String text, float ulx, float uly, float lrx, float lry) {
    this.text = text;
    this.ulx = ulx;
    this.uly = uly;
    this.lrx = lrx;
    this.lry = lry;
  }
}
