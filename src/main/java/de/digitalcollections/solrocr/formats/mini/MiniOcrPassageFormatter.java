package de.digitalcollections.solrocr.formats.mini;

import com.google.common.base.Strings;
import de.digitalcollections.solrocr.formats.OcrPassageFormatter;
import de.digitalcollections.solrocr.iter.IterableCharSequence;
import de.digitalcollections.solrocr.iter.TagBreakIterator;
import de.digitalcollections.solrocr.model.OcrBox;
import de.digitalcollections.solrocr.model.OcrPage;
import de.digitalcollections.solrocr.model.OcrSnippet;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.text.StringEscapeUtils;
import org.apache.lucene.search.uhighlight.Passage;

public class MiniOcrPassageFormatter extends OcrPassageFormatter {
  private final static Pattern wordPat = Pattern.compile(
      "<w x=\"(?<x>1?\\.?\\d+?) (?<y>1?\\.?\\d+?) (?<w>1?\\.?\\d+?) (?<h>1?\\.?\\d+?)\">(?<text>.+?)</w>");
  private final static Pattern pagePat = Pattern.compile(
      "<p xml:id=\"(?<pageId>.+?)\" ?(?:wh=\"(?<w>\\d+) (?<h>\\d+)\")?>");

  private final TagBreakIterator pageIter = new TagBreakIterator("p");

  public MiniOcrPassageFormatter(String startHlTag, String endHlTag, boolean absoluteHighlights) {
    super(startHlTag, endHlTag, absoluteHighlights);
  }

  @Override
  public OcrPage determineStartPage(String xmlFragment, int startOffset, IterableCharSequence content) {
    pageIter.setText(content);
    int pageOffset = pageIter.preceding(startOffset);
    String pageFragment = content.subSequence(
        pageOffset, Math.min(pageOffset + 128, content.length())).toString();
    Matcher m = pagePat.matcher(pageFragment);
    if (m.find()) {
      Dimension dims = null;
      if (!Strings.isNullOrEmpty(m.group("w")) && !Strings.isNullOrEmpty(m.group("h"))) {
        try {
          dims = new Dimension(Integer.parseInt(m.group("w")), Integer.parseInt(m.group("h")));
        } catch (NumberFormatException e) {
          // NOP, we only care about integer dimensions
        }
      }
      return new OcrPage(m.group("pageId"), dims);
    }
    return null;
  }

  @Override
  protected TreeMap<Integer, OcrPage> parsePages(String ocrFragment) {
    TreeMap<Integer, OcrPage> map = new TreeMap<>();
    Matcher m = pagePat.matcher(ocrFragment);
    while (m.find()) {
      Dimension dims = null;
      if (!Strings.isNullOrEmpty(m.group("w")) && !Strings.isNullOrEmpty(m.group("h"))) {
        try {
          dims = new Dimension(Integer.parseInt(m.group("w")), Integer.parseInt(m.group("h")));
        } catch (NumberFormatException e) {
          // NOP, we only care about integer dimensions
        }
      }
      map.put(m.start(), new OcrPage(m.group("pageId"), dims));
    }
    return map;
  }

  @Override
  protected void addHighlightsToSnippet(List<List<OcrBox>> hlSpans, OcrSnippet snippet) {
    // No scaling necessary with absolute highlights since we don't modify the coordinates
    if (this.absoluteHighlights) {
      super.addHighlightsToSnippet(hlSpans, snippet);
      return;
    }

    // Handle relative coordinates, coordinates need scaling
    hlSpans.stream().flatMap(Collection::stream)
        .forEach(box -> {
          Optional<OcrBox> region = snippet.getSnippetRegions().stream().filter(r -> r.contains(box)).findFirst();
          if (!region.isPresent()) {
            return;
          }
          float xOffset = region.get().getUlx();
          float yOffset = region.get().getUly();
          float snipWidth = region.get().getLrx() - xOffset;
          float snipHeight = region.get().getLry() - yOffset;
          box.setUlx(truncateFloat((box.getUlx() - xOffset) / snipWidth));
          box.setLrx(truncateFloat((box.getLrx() - xOffset) / snipWidth));
          box.setUly(truncateFloat((box.getUly() - yOffset) / snipHeight));
          box.setLry(truncateFloat((box.getLry() - yOffset) / snipHeight));
          box.setParentRegionIdx(snippet.getSnippetRegions().indexOf(region.get()));
          // Remove the highlighting tags from the text
          box.setText(box.getText().replaceAll(startHlTag, "").replaceAll(endHlTag, ""));
        });
    hlSpans.forEach(span -> snippet.addHighlightSpan(this.mergeBoxes(span)));
  }

  @Override
  protected List<OcrBox> parseWords(String ocrFragment, TreeMap<Integer, OcrPage> pages, String startPage) {
    List<OcrBox> wordBoxes = new ArrayList<>();
    boolean inHighlight = false;
    Matcher m = wordPat.matcher(ocrFragment);
    while (m.find()) {
      String pageId = startPage;
      if (pages.floorKey(m.start()) != null) {
        pageId = pages.floorEntry(m.start()).getValue().id;
      }
      float x = Float.parseFloat(m.group("x"));
      float y = Float.parseFloat(m.group("y"));
      float width = Float.parseFloat(m.group("w"));
      float height = Float.parseFloat(m.group("h"));
      String text = StringEscapeUtils.unescapeXml(m.group("text"));
      if (text.contains(startHlTag)) {
        inHighlight = true;
      }
      wordBoxes.add(new OcrBox(text, pageId, x, y, x + width, y + height, inHighlight));
      boolean endOfHl = (
          text.contains(endHlTag)
          || ocrFragment.substring(m.end(), Math.min(m.end() + endHlTag.length(), ocrFragment.length()))
              .equals(endHlTag));
      if (endOfHl) {
        inHighlight = false;
      }
    }
    return wordBoxes;
  }

  private float truncateFloat(float num) {
    return (float) Math.floor(num * 10000) / 10000;
  }

  @Override
  public Object format(Passage[] passages, String content) {
    throw new UnsupportedOperationException();
  }
}
