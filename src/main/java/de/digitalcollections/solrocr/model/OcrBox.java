package de.digitalcollections.solrocr.model;

import java.util.Comparator;
import java.util.List;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.common.util.SimpleOrderedMap;

@SuppressWarnings({"rawtypes", "unchecked"})
public class OcrBox implements Comparable<OcrBox> {
  private final Comparator<OcrBox> comparator = Comparator
      .comparing(OcrBox::getPageId)
      .thenComparingDouble(OcrBox::getUly)
      .thenComparingDouble(OcrBox::getUlx);

  private String text;
  private String pageId;
  private float ulx;
  private float uly;
  private float lrx;
  private float lry;
  private boolean inHighlight;
  private Integer parentRegionIdx;


  public OcrBox(String text, String pageId, float ulx, float uly, float lrx, float lry,
                boolean inHighlight) {
    this.text = text;
    this.pageId = pageId;
    this.ulx = ulx;
    this.uly = uly;
    this.lrx = lrx;
    this.lry = lry;
    this.inHighlight = inHighlight;
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
    if (this.getParentRegionIdx() != null) {
      snipRegion.add("parentRegionIdx", this.getParentRegionIdx());
    }
    return snipRegion;
  }

  public NamedList toNamedList(List<OcrPage> pages) {
    SimpleOrderedMap snipRegion = (SimpleOrderedMap) this.toNamedList();
    if (this.getPageId() != null) {
      snipRegion.remove("pageId");
      for (int i=0; i < pages.size(); i++) {
        OcrPage p = pages.get(i);
        if (p.id.equals(this.getPageId())) {
          snipRegion.add("pageIdx", i);
        }
      }
    }
    return snipRegion;
  }

  @Override
  public String toString() {
    return String.format(
        "OcrBox{text='%s', pageId='%s', regionIdx=%d, ulx=%s, uly=%s, lrx=%s, lry=%s}",
        getText(), getPageId(), getParentRegionIdx(), getUlx(), getUly(), getLrx(), getLry());
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

  public float getWidth() {
    return lrx - ulx;
  }

  public float getHeight() {
    return lry - uly;
  }

  public boolean isInHighlight() {
    return inHighlight;
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

  public void setInHighlight(boolean inHighlight) {
    this.inHighlight = inHighlight;
  }

  public Integer getParentRegionIdx() {
    return parentRegionIdx;
  }

  public void setParentRegionIdx(int parentRegionIdx) {
    this.parentRegionIdx = parentRegionIdx;
  }

  public boolean contains(OcrBox other) {
    return
        other.ulx >= this.ulx
        && other.uly >= this.uly
        && other.lrx <= this.lrx
        && other.lry <= this.lry;
  }
}
