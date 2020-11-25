package de.digitalcollections.solrocr.formats.alto;

import com.google.common.collect.ImmutableMap;
import de.digitalcollections.solrocr.formats.OcrParser;
import de.digitalcollections.solrocr.iter.TagBreakIterator;
import de.digitalcollections.solrocr.model.OcrBlock;
import de.digitalcollections.solrocr.model.OcrFormat;
import de.digitalcollections.solrocr.model.OcrPage;
import java.awt.Dimension;
import java.io.Reader;
import java.text.BreakIterator;
import java.util.Map;
import javax.xml.stream.XMLStreamException;

public class AltoFormat implements OcrFormat {
  private static final Map<OcrBlock, String> blockTagMapping = ImmutableMap.of(
      OcrBlock.PAGE, "Page",
      //OcrBlock.SECTION, "",
      OcrBlock.BLOCK, "TextBlock",
      OcrBlock.LINE, "TextLine",
      OcrBlock.WORD, "String");

  @Override
  public BreakIterator getBreakIterator(OcrBlock blockType) {
    String breakTag = blockTagMapping.get(blockType);
    return new TagBreakIterator(breakTag);
  }

  @Override
  public OcrParser getParser(Reader input, OcrParser.ParsingFeature... features) {
    try {
      return new AltoParser(input, features);
    } catch (XMLStreamException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public OcrPage parsePageFragment(String pageFragment) {
    pageFragment = pageFragment.substring(0, pageFragment.indexOf(">"));
    String[] elemParts = pageFragment.split(" ");
    String width = null;
    String height = null;
    String id = null;
    for (String elemPart : elemParts) {
      String[] parts = elemPart.split("=");
      switch (parts[0]) {
        case "WIDTH":
          width = parts[1].substring(1, parts[1].length() - 1);
          break;
        case "HEIGHT":
          height = parts[1].substring(1, parts[1].length() - 1);
          break;
        case "ID":
          id = parts[1].substring(1, parts[1].length() - 1);
          break;
      }
      if (id != null && width != null && height != null) {
        break;
      }
    }
    Dimension dims = null;
    if (width != null && height != null) {
      try {
        dims = new Dimension((int) Double.parseDouble(width), (int) Double.parseDouble(height));
      } catch (NumberFormatException e) {
        // NOP, we're only interested in integer dimensions
      }
    }
    return new OcrPage(id, dims);
  }

  @Override
  public boolean hasFormat(String ocrChunk) {
    // Check if the chunk contains any ALTO tags
    return ocrChunk.contains("<alto")
        || blockTagMapping.values().stream()
            .anyMatch(t -> ocrChunk.contains("<" + t));
  }

  @Override
  public int getLastContentStartIdx(String content) {
    int contentIdx = content.lastIndexOf("CONTENT=");
    if (contentIdx >= 0) {
      contentIdx += 9;
    }
    return contentIdx;
  }

  @Override
  public int getFirstContentEndIdx(String content) {
    int singleQuoteIdx = content.indexOf("'");
    int doubleQuoteIdx = content.indexOf("\"");
    if (singleQuoteIdx < 0) {
      return doubleQuoteIdx;
    } else if (doubleQuoteIdx < 0) {
      return singleQuoteIdx;
    }
    return Math.min(singleQuoteIdx, doubleQuoteIdx);
  }
}
