package org.mdz.search.solrocr.formats;

import java.util.ArrayList;
import java.util.List;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.common.util.SimpleOrderedMap;
import org.mdz.search.solrocr.util.OcrBox;

/**
 * A structured representation of a highlighted OCR snippet.
 *
 * @param <T> the {@link Number} implementation that is used for the positions
 */
public class OcrSnippet<T extends Number> {
  private final String text;
  private final String pageId;
  private final OcrBox<T> snippetRegion;
  private final List<OcrBox<T>> highlightRegions;
  private float score;

  /**
   * Create a new snippet on the given region on the page along with its plaintext.
   * @param text plaintext version of the highlighted page text with highlighting tags
   * @param pageId identifier for the page the snippet is located on
   * @param snippetRegion region of the page the snippet is located in
   */
  public OcrSnippet(String text, String pageId, OcrBox<T> snippetRegion) {
    this.text = text;
    this.pageId = pageId;
    this.snippetRegion = snippetRegion;
    this.highlightRegions = new ArrayList<>();
  }

  /** Add a new highlighted region in the snippet.
   *
   * <strong>Note that the region should be relative to the snippet region!</strong>
   *
   * @param region Location of the highlighted region <strong>relative to the snippet region</strong>.
   */
  public void addHighlightRegion(OcrBox<T> region) {
    this.highlightRegions.add(region);
  }

  /** Get the plaintext version of the highlighted page text with highlighting tags */
  public String getText() {
    return text;
  }

  /** Get the region of the page that the snippes is located in */
  public OcrBox<T> getSnippetRegion() {
    return snippetRegion;
  }

  /**
   * Get the highlighted regions of the snippet region.
   *
   * <strong>The highlighted regions are relative to the snippet region, not to the page.</strong>
   */
  public List<OcrBox<T>> getHighlightRegions() {
    return highlightRegions;
  }

  /** Get the identifier of the page the snippet is located on */
  public String getPageId() {
    return pageId;
  }

  /** Get the score of the passage, compared to all other passages in the document */
  public float getScore() {
    return score;
  }

  /** Set the score of the passage, compared to all other passages in the document */
  public void setScore(float score) {
    this.score = score;
  }

  /** Convert the snippet to a {@link NamedList} that is used by Solr to populate the response. */
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
}
