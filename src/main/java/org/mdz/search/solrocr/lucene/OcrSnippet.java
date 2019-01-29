package org.mdz.search.solrocr.lucene;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;

public class OcrSnippet {
  private final String text;
  private final int pageNo;
  private final Rectangle snippetRegion;
  private final List<Rectangle> highlightRegions;

  public OcrSnippet(String text, int pageNo, Rectangle snippetRegion) {
    this.text = text;
    this.pageNo = pageNo;
    this.snippetRegion = snippetRegion;
    this.highlightRegions = new ArrayList<>();
  }

  public void addHighlightRegion(Rectangle region) {
    this.highlightRegions.add(region);
  }

  public String getText() {
    return text;
  }

  public Rectangle getSnippetRegion() {
    return snippetRegion;
  }

  public List<Rectangle> getHighlightRegions() {
    // TODO: Validate that region matches the snippet region, e.g. that the x, y points are in the region
    return highlightRegions;
  }

  public int getPageNo() {
    return pageNo;
  }
}
