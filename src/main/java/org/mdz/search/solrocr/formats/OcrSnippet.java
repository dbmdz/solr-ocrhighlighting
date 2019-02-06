package org.mdz.search.solrocr.formats;

import java.util.ArrayList;
import java.util.List;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.common.util.SimpleOrderedMap;
import org.mdz.search.solrocr.util.OcrBox;

public class OcrSnippet<T extends Number> {
  private final String text;
  private final String pageId;
  private final OcrBox<T> snippetRegion;
  private final List<OcrBox<T>> highlightRegions;
  private float score;

  public OcrSnippet(String text, String pageId, OcrBox<T> snippetRegion) {
    this.text = text;
    this.pageId = pageId;
    this.snippetRegion = snippetRegion;
    this.highlightRegions = new ArrayList<>();
  }

  public void addHighlightRegion(OcrBox<T> region) {
    this.highlightRegions.add(region);
  }

  public String getText() {
    return text;
  }

  public OcrBox<T> getSnippetRegion() {
    return snippetRegion;
  }

  public List<OcrBox<T>> getHighlightRegions() {
    return highlightRegions;
  }

  public String getPageId() {
    return pageId;
  }

  public NamedList toNamedList() {
    SimpleOrderedMap m = new SimpleOrderedMap();
    if (this.pageId != null) {
      m.add("page", this.getPageId());
    }
    m.add("text", this.getText());
    m.add("score", this.getScore());
    SimpleOrderedMap snipRegion = new SimpleOrderedMap();
    if (this.getSnippetRegion() != null) {
      snipRegion.add("x", this.getSnippetRegion().x);
      snipRegion.add("y", this.getSnippetRegion().y);
      snipRegion.add("w", this.getSnippetRegion().width);
      snipRegion.add("h", this.getSnippetRegion().height);
    }
    m.add("region", snipRegion);
    if (this.getHighlightRegions() != null) {
      SimpleOrderedMap[] highlights = this.getHighlightRegions().stream()
          .map(r -> {
            SimpleOrderedMap hlMap = new SimpleOrderedMap();
            hlMap.add("x", r.x);
            hlMap.add("y", r.y);
            hlMap.add("w", r.width);
            hlMap.add("h", r.height);
            return hlMap;
          }).toArray(SimpleOrderedMap[]::new);
      m.add("highlights", highlights);
    }
    return m;
  }

  public float getScore() {
    return score;
  }

  public void setScore(float score) {
    this.score = score;
  }
}
