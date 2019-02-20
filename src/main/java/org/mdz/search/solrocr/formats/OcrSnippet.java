package org.mdz.search.solrocr.formats;

import java.util.ArrayList;
import java.util.List;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.common.util.SimpleOrderedMap;
import org.mdz.search.solrocr.util.OcrBox;

/** A structured representation of a highlighted OCR snippet. */
public class OcrSnippet {
  private final String text;
  private final String pageId;
  private final OcrBox snippetRegion;
  private final List<OcrBox[]> highlightRegions;
  private float score;

  /**
   * Create a new snippet on the given region on the page along with its plaintext.
   * @param text plaintext version of the highlighted page text with highlighting tags
   * @param pageId identifier for the page the snippet is located on
   * @param snippetRegion region of the page the snippet is located in
   */
  public OcrSnippet(String text, String pageId, OcrBox snippetRegion) {
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
  public void addHighlightRegion(List<OcrBox> region) {
    this.highlightRegions.add(region.toArray(new OcrBox[0]));
  }

  /** Get the plaintext version of the highlighted page text with highlighting tags */
  public String getText() {
    return text;
  }

  /** Get the region of the page that the snippes is located in */
  public OcrBox getSnippetRegion() {
    return snippetRegion;
  }

  /**
   * Get the highlighted regions of the snippet region.
   *
   * <strong>The highlighted regions are relative to the snippet region, not to the page.</strong>
   */
  public List<OcrBox[]> getHighlightRegions() {
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

  private void addDimension(SimpleOrderedMap map, String name, float val) {
    if (val >= 1 || val == 0) {
      map.add(name, (int) val);
    } else {
      map.add(name, val);
    }
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
      addDimension(snipRegion, "ulx", this.getSnippetRegion().ulx);
      addDimension(snipRegion, "uly", this.getSnippetRegion().uly);
      addDimension(snipRegion, "lrx", this.getSnippetRegion().lrx);
      addDimension(snipRegion, "lry", this.getSnippetRegion().lry);
    }
    m.add("region", snipRegion);
    if (this.getHighlightRegions() != null) {
      List<SimpleOrderedMap[]> highlights = new ArrayList<>();
      for (OcrBox[] region : this.getHighlightRegions()) {
        SimpleOrderedMap[] regionBoxes = new SimpleOrderedMap[region.length];
        for (int i=0; i < region.length; i++) {
          OcrBox ocrBox = region[i];
          SimpleOrderedMap box = new SimpleOrderedMap();
          if (ocrBox.text != null) {
            box.add("text", ocrBox.text);
          }
          addDimension( box, "ulx", ocrBox.ulx);
          addDimension( box, "uly", ocrBox.uly);
          addDimension( box, "lrx", ocrBox.lrx);
          addDimension( box, "lry", ocrBox.lry);
          regionBoxes[i] = box;
        }
        highlights.add(regionBoxes);
      }
      m.add("highlights", highlights);
    }
    return m;
  }
}
