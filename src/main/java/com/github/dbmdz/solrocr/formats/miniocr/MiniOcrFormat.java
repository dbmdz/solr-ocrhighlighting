package com.github.dbmdz.solrocr.formats.miniocr;

import com.github.dbmdz.solrocr.formats.OcrParser;
import com.github.dbmdz.solrocr.iter.BreakLocator;
import com.github.dbmdz.solrocr.iter.TagBreakLocator;
import com.github.dbmdz.solrocr.model.OcrBlock;
import com.github.dbmdz.solrocr.model.OcrFormat;
import com.github.dbmdz.solrocr.model.OcrPage;
import com.github.dbmdz.solrocr.reader.SourceReader;
import com.google.common.collect.ImmutableMap;
import java.awt.Dimension;
import java.io.Reader;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.stream.XMLStreamException;

public class MiniOcrFormat implements OcrFormat {
  private static final Pattern pagePat =
      Pattern.compile(
          "<p (?:xml)?:id=(\"|')(?<pageId>.+?)(\"|') ?(?:wh=(\"|')(?<width>\\d+) (?<height>\\d+)(\"|'))?>");
  private static final Map<OcrBlock, String> blockTagMapping =
      ImmutableMap.of(
          OcrBlock.PAGE, "p",
          OcrBlock.SECTION, "s",
          OcrBlock.BLOCK, "b",
          OcrBlock.LINE, "l",
          OcrBlock.WORD, "w");

  public MiniOcrFormat() {}

  @Override
  public BreakLocator getBreakLocator(SourceReader reader, OcrBlock... blockTypes) {
    // FIXME: MiniOCR currently presupposes that the desired  block type exists, i.e. if you say
    // "break on paragraph", we're just assuming that there are actually paragraphs in the OCR.
    // If they're not, there will not be a break. It would be better if we checked all of the passed
    // blocks.
    String breakTag = blockTagMapping.get(blockTypes[0]);
    return new TagBreakLocator(reader, breakTag);
  }

  @Override
  public OcrParser getParser(Reader input, OcrParser.ParsingFeature... features) {
    try {
      return new MiniOcrParser(input, features);
    } catch (XMLStreamException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public OcrPage parsePageFragment(String pageFragment) {
    Matcher m = pagePat.matcher(pageFragment);
    if (!m.find()) {
      return null;
    }
    Dimension dims = null;
    if (m.group("width") != null && m.group("height") != null) {
      dims = new Dimension(Integer.parseInt(m.group("width")), Integer.parseInt(m.group("height")));
    }
    String pageId = m.group("pageId");
    return new OcrPage(pageId, dims);
  }

  @Override
  public boolean hasFormat(String ocrChunk) {
    return blockTagMapping.values().stream()
        .filter(t -> !t.equals("p")) // leads to false positives on hOCR content
        .anyMatch(t -> ocrChunk.contains("<" + t + " ") || ocrChunk.contains("<" + t + ">"));
  }

  @Override
  public int getLastContentStartIdx(String content) {
    return content.lastIndexOf(">") + 1;
  }

  @Override
  public int getFirstContentEndIdx(String content) {
    return content.indexOf("</");
  }
}
