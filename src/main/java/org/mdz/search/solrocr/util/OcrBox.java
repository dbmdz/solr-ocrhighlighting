package org.mdz.search.solrocr.util;

import java.util.Comparator;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.common.util.SimpleOrderedMap;

public class OcrBox implements Comparable<OcrBox> {
  private final Comparator<OcrBox> comparator = Comparator
      .comparing(OcrBox::getPageId)
      .thenComparingDouble(OcrBox::getUly)
      .thenComparingDouble(OcrBox::getUlx);

  private String pageId;
  private String text;
  private float ulx;
  private float uly;
  private float lrx;
  private float lry;
  private boolean isHighlight;


  public OcrBox(String text, String pageId, float ulx, float uly, float lrx, float lry, boolean isHighlight) {
    this.text = text;
    this.pageId = pageId;
    this.ulx = ulx;
    this.uly = uly;
    this.lrx = lrx;
    this.lry = lry;
    this.isHighlight = isHighlight;
  }

  private void addDimension(SimpleOrderedMap map, String name, float val) {
    if (val >= 1 || val == 0) {
      map.add(name, (int) val);
    } else {
      map.add(name, val);
    }
  }

  public NamedList toNamedList() {
    SimpleOrderedMap snipRegion = new SimpleOrderedMap();
    addDimension(snipRegion, "ulx", this.getUlx());
    addDimension(snipRegion, "uly", this.getUly());
    addDimension(snipRegion, "lrx", this.getLrx());
    addDimension(snipRegion, "lry", this.getLry());
    if (this.getText() != null) {
      snipRegion.add("text", this.getText());
    }
    if (this.getPageId() != null) {
      snipRegion.add("page", this.getPageId());
    }
    return snipRegion;
  }

  @Override
  public String toString() {
    return String.format("OcrBox{text='%s', page='%s', ulx=%s, uly=%s, lrx=%s, lry=%s}",
                         getText(), getPageId(), getUlx(), getUly(), getLrx(), getLry());
  }


  @Override
  public int compareTo(OcrBox o) {
    return comparator.compare(this, o);
  }

  public String getPageId() {
    return pageId;
  }

  public String getText() {
    return text;
  }

  public float getUlx() {
    return ulx;
  }

  public float getUly() {
    return uly;
  }

  public float getLrx() {
    return lrx;
  }

  public float getLry() {
    return lry;
  }

  public boolean isHighlight() {
    return isHighlight;
  }

  public void setText(String text) {
    this.text = text;
  }

  public void setPageId(String pageId) {
    this.pageId = pageId;
  }

  public void setUlx(float ulx) {
    this.ulx = ulx;
  }

  public void setUly(float uly) {
    this.uly = uly;
  }

  public void setLrx(float lrx) {
    this.lrx = lrx;
  }

  public void setLry(float lry) {
    this.lry = lry;
  }

  public void setHighlight(boolean highlight) {
    isHighlight = highlight;
  }
}
