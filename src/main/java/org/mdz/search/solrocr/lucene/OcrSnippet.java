package org.mdz.search.solrocr.lucene;

import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.common.util.SimpleOrderedMap;

public class OcrSnippet {
  private final String text;
  private final String pageId;
  private final Rectangle2D snippetRegion;
  private final List<Rectangle2D> highlightRegions;
  private float score;

  public OcrSnippet(String text, String pageId, Rectangle2D snippetRegion) {
    this.text = text;
    this.pageId = pageId;
    this.snippetRegion = snippetRegion;
    this.highlightRegions = new ArrayList<>();
  }

  public void addHighlightRegion(Rectangle2D region) {
    this.highlightRegions.add(region);
  }

  public String getText() {
    return text;
  }

  public Rectangle2D getSnippetRegion() {
    return snippetRegion;
  }

  public List<Rectangle2D> getHighlightRegions() {
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
      snipRegion.add("x", this.getSnippetRegion().getX());
      snipRegion.add("y", this.getSnippetRegion().getY());
      snipRegion.add("w", this.getSnippetRegion().getWidth());
      snipRegion.add("h", this.getSnippetRegion().getHeight());
    }
    m.add("region", snipRegion);
    if (this.getHighlightRegions() != null) {
      SimpleOrderedMap[] highlights = this.getHighlightRegions().stream()
          .map(r -> {
            SimpleOrderedMap hlMap = new SimpleOrderedMap();
            hlMap.add("x", r.getX());
            hlMap.add("y", r.getY());
            hlMap.add("w", r.getWidth());
            hlMap.add("h", r.getHeight());
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
