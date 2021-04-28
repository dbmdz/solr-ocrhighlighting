package de.digitalcollections.solrocr.formats.alto;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Range;
import de.digitalcollections.solrocr.formats.OcrParser;
import de.digitalcollections.solrocr.iter.BreakLocator;
import de.digitalcollections.solrocr.iter.IterableCharSequence;
import de.digitalcollections.solrocr.iter.TagBreakLocator;
import de.digitalcollections.solrocr.model.OcrBlock;
import de.digitalcollections.solrocr.model.OcrFormat;
import de.digitalcollections.solrocr.model.OcrPage;
import java.awt.Dimension;
import java.io.Reader;
import java.util.Map;
import java.util.stream.IntStream;
import javax.xml.stream.XMLStreamException;

public class AltoFormat implements OcrFormat {
  private static final Map<OcrBlock, String> blockTagMapping =
      ImmutableMap.of(
          OcrBlock.PAGE, "Page",
          // OcrBlock.SECTION, "",
          OcrBlock.BLOCK, "TextBlock",
          OcrBlock.LINE, "TextLine",
          OcrBlock.WORD, "String");

  @Override
  public BreakLocator getBreakLocator(IterableCharSequence text, OcrBlock... blockTypes) {
    // NOTE: The ALTO hierarchy we support is pretty rigid, i.e. Page > TextBlock > TextLine >
    // String is a given, hence we only grab the lowest-hierarchy block and call it a day
    String breakTag = blockTagMapping.get(blockTypes[0]);
    return new TagBreakLocator(text, breakTag);
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
    // Poor/lean man's XML parsing
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
        || blockTagMapping.values().stream().anyMatch(t -> ocrChunk.contains("<" + t));
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

  @Override
  public Range<Integer> getContainingWordLimits(String fragment, int position) {
    int doubleStartIdx = fragment.lastIndexOf("CONTENT=\"", position) + 9;
    int singleStartIdx = fragment.lastIndexOf("CONTENT='", position) + 9;
    int altStartIdx = fragment.lastIndexOf("<ALTERNATIVE>", position) + 13;
    char attribChar;
    int startIdx = IntStream.of(doubleStartIdx, singleStartIdx, altStartIdx).max().getAsInt();
    if (startIdx == doubleStartIdx) {
      attribChar = '"';
    } else if (startIdx == singleStartIdx) {
      attribChar = '\'';
    } else {
      attribChar = '<';
    }
    return Range.closedOpen(startIdx, fragment.indexOf(attribChar, position));
  }
}
