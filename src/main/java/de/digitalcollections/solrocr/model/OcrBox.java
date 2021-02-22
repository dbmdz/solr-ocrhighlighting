package de.digitalcollections.solrocr.model;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.common.util.SimpleOrderedMap;

@SuppressWarnings({"rawtypes", "unchecked"})
public class OcrBox implements Comparable<OcrBox> {
  private final Comparator<OcrBox> comparator = Comparator
      .comparing(OcrBox::getPage)
      .thenComparingDouble(OcrBox::getUly)
      .thenComparingDouble(OcrBox::getUlx);

  private String text;
  private int textOffset = -1;
  private final List<String> alternatives = new ArrayList<>();
  private final List<Integer> alternativeOffsets = new ArrayList<>();
  private String trailingChars = "";
  private OcrPage page;
  private float ulx = -1;
  private float uly = -1;
  private float lrx = -1;
  private float lry = -1;
  private UUID highlightSpan;
  private Integer parentRegionIdx;
  private String dehyphenatedForm;
  private Integer dehyphenatedOffset;
  private Boolean hyphenStart;
  private Double confidence;

  public OcrBox() {
  }

  // FIXME: Is this really ulx/uly?
  public OcrBox(String text, OcrPage page, float ulx, float uly, float lrx, float lry,
                UUID highlightSpan) {
    this.text = text;
    this.page = page;
    this.ulx = ulx;
    this.uly = uly;
    this.lrx = lrx;
    this.lry = lry;
    this.highlightSpan = highlightSpan;
  }

  private static void addDimension(SimpleOrderedMap map, String name, float val) {
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
    if (this.page != null) {
      snipRegion.remove("pageId");
      for (int i=0; i < pages.size(); i++) {
        OcrPage p = pages.get(i);
        if (p.id.equals(this.page.id)) {
          snipRegion.add("pageIdx", i);
        }
      }
    }
    return snipRegion;
  }

  @Override
  public String toString() {
    final StringBuilder sb = new StringBuilder("OcrBox{");
    sb.append("text='").append(text).append('\'');
    if (textOffset >= 0) {
      sb.append('@').append(textOffset);
    }
    if (!this.alternatives.isEmpty()) {
      sb.append(", alternatives={");
      for (int i = 0; i < alternatives.size(); i++) {
        sb.append('\'').append(alternatives.get(i)).append('\'');
        if (!this.alternativeOffsets.isEmpty()) {
          sb.append('@').append(this.alternativeOffsets.get(i));
        }
        if (i != alternatives.size() - 1) {
          sb.append(',');
        }
      }
      sb.append('}');
    }
    if (this.trailingChars != null) {
      sb.append(", trailingChars='").append(trailingChars).append('\'');
    }
    if (this.page != null) {
      sb.append(", pageId='").append(page.id).append('\'');
    }
    if (this.ulx >= 0) {
      sb.append(", ulx=").append(ulx);
    }
    if (this.uly >= 0) {
      sb.append(", uly=").append(uly);
    }
    if (this.lrx >= 0) {
      sb.append(", lrx=").append(lrx);
    }
    if (this.lry >= 0) {
      sb.append(", lry=").append(lry);
    }
    if (this.highlightSpan != null) {
      sb.append(", highlightSpan=").append(highlightSpan);
    }
    if (this.dehyphenatedForm != null) {
      sb.append(", dehyphenatedForm='").append(dehyphenatedForm).append('\'');
      if (this.dehyphenatedOffset != null && this.dehyphenatedOffset >= 0) {
        sb.append('@').append(dehyphenatedOffset);
      }
    }
    if (this.hyphenStart != null) {
      sb.append(", hyphenStart=").append(hyphenStart);
    }
    if (this.confidence != null) {
      sb.append(", confidence=").append(confidence);
    }
    if (this.parentRegionIdx != null) {
      sb.append(", parentRegionIdx=").append(parentRegionIdx);
    }
    sb.append('}');
    return sb.toString();
  }

  @Override
  public int compareTo(OcrBox o) {
    return comparator.compare(this, o);
  }

  public OcrPage getPage() {
    return page;
  }

  public String getText() {
    return text;
  }

  public List<String> getAlternatives() {
    return alternatives;
  }

  public List<Integer> getAlternativeOffsets() {
    return alternativeOffsets;
  }

  public String getTrailingChars() {
    return trailingChars;
  }

  public int getTextOffset() {
    return textOffset;
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

  public UUID getHighlightSpan() {
    return highlightSpan;
  }

  public boolean isInHighlight() {
    return highlightSpan != null;
  }

  public Integer getDehyphenatedOffset() {
    return dehyphenatedOffset;
  }

  public boolean isHyphenated() {
    return hyphenStart != null;
  }

  public String getDehyphenatedForm() {
    return dehyphenatedForm;
  }

  public Boolean isHyphenStart() {
    return isHyphenated() && hyphenStart;
  }

  public void setText(String text) {
    this.text = text;
  }

  public void addAlternative(String alternative, Integer offset) {
    this.alternatives.add(alternative);
    if (offset != null) {
      this.alternativeOffsets.add(offset);
    }
  }

  public void setTrailingChars(String trailingChars) {
    this.trailingChars = trailingChars;
  }

  public void setTextOffset(int textOffset) {
    this.textOffset = textOffset;
  }

  public Double getConfidence() {
    return confidence;
  }

  public void setConfidence(Double confidence) {
    this.confidence = confidence;
  }

  public void setPage(OcrPage page) {
    this.page = page;
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

  public void setHighlightSpan(UUID highlightId) {
    this.highlightSpan = highlightId;
  }

  public void setHyphenInfo(Boolean hyphenStart, String dehyphenated) {
    this.hyphenStart = hyphenStart;
    this.dehyphenatedForm = dehyphenated;
  }

  public void setDehyphenatedOffset(Integer dehyphenatedOffset) {
    this.dehyphenatedOffset = dehyphenatedOffset;
  }

  public Integer getParentRegionIdx() {
    return parentRegionIdx;
  }

  public void setParentRegionIdx(int parentRegionIdx) {
    this.parentRegionIdx = parentRegionIdx;
  }

  public boolean contains(OcrBox other) {
    return
        other.page == this.page
        && other.ulx >= this.ulx
        && other.uly >= this.uly
        && other.lrx <= this.lrx
        && other.lry <= this.lry;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    OcrBox ocrBox = (OcrBox) o;
    return textOffset == ocrBox.textOffset &&
        Float.compare(ocrBox.ulx, ulx) == 0 &&
        Float.compare(ocrBox.uly, uly) == 0 &&
        Float.compare(ocrBox.lrx, lrx) == 0 &&
        Float.compare(ocrBox.lry, lry) == 0 &&
        Objects.equals(text, ocrBox.text) &&
        Objects.equals(alternatives, ocrBox.alternatives) &&
        Objects.equals(alternativeOffsets, ocrBox.alternativeOffsets) &&
        Objects.equals(trailingChars, ocrBox.trailingChars) &&
        Objects.equals(page, ocrBox.page) &&
        Objects.equals(highlightSpan, ocrBox.highlightSpan) &&
        Objects.equals(parentRegionIdx, ocrBox.parentRegionIdx) &&
        Objects.equals(dehyphenatedForm, ocrBox.dehyphenatedForm) &&
        Objects.equals(dehyphenatedOffset, ocrBox.dehyphenatedOffset) &&
        Objects.equals(hyphenStart, ocrBox.hyphenStart) &&
        Objects.equals(confidence, ocrBox.confidence);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        text, textOffset, alternatives, alternativeOffsets, trailingChars, page, ulx, uly, lrx, lry, highlightSpan,
        parentRegionIdx, dehyphenatedForm, dehyphenatedOffset, hyphenStart, confidence);
  }
}
