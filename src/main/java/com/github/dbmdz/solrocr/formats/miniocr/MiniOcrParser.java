package com.github.dbmdz.solrocr.formats.miniocr;

import com.github.dbmdz.solrocr.formats.OcrParser;
import com.github.dbmdz.solrocr.model.OcrBox;
import com.github.dbmdz.solrocr.model.OcrPage;
import java.awt.Dimension;
import java.io.Reader;
import java.lang.invoke.MethodHandles;
import java.util.Set;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import org.apache.commons.lang3.StringUtils;
import org.codehaus.stax2.XMLStreamReader2;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MiniOcrParser extends OcrParser {
  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
  private static final char alternativeMarker = '⇿';

  private boolean noMoreWords;
  private OcrPage currentPage;
  private OcrBox hyphenEnd = null;

  public MiniOcrParser(Reader input, OcrParser.ParsingFeature... features)
      throws XMLStreamException {
    super(input, features);
  }

  @Override
  protected OcrBox readNext(XMLStreamReader2 xmlReader, Set<ParsingFeature> features)
      throws XMLStreamException {
    if (hyphenEnd != null) {
      OcrBox out = this.hyphenEnd;
      this.hyphenEnd = null;
      return out;
    }

    if (xmlReader.getEventType() != XMLStreamConstants.START_ELEMENT
        || !"w".equals(xmlReader.getLocalName())) {
      this.seekToNextWord(xmlReader, features.contains(ParsingFeature.PAGES));
    }

    if (noMoreWords) {
      return null;
    }

    OcrBox box = new OcrBox();
    if (features.contains(ParsingFeature.COORDINATES)) {
      String[] coords = xmlReader.getAttributeValue("", "x").split(" ");
      if (coords.length > 0) {
        box.setUlx(Float.parseFloat(coords[0]));
      }
      if (coords.length > 1) {
        box.setUly(Float.parseFloat(coords[1]));
      }
      if (coords.length > 2) {
        box.setLrx(box.getUlx() + Float.parseFloat(coords[2]));
      }
      if (coords.length > 3) {
        box.setLry(box.getUly() + Float.parseFloat(coords[3]));
      } else {
        log.warn("x attribute is incomplete: '{}'", String.join(" ", coords));
      }
    }
    if (features.contains(ParsingFeature.CONFIDENCE)) {
      String confidence = xmlReader.getAttributeValue("", "c");
      if (confidence != null && !confidence.isEmpty()) {
        box.setConfidence(Double.parseDouble(confidence));
      }
    }
    if (features.contains(ParsingFeature.TEXT)) {
      this.parseText(
          xmlReader,
          box,
          features.contains(ParsingFeature.HIGHLIGHTS),
          features.contains(ParsingFeature.OFFSETS),
          features.contains(ParsingFeature.ALTERNATIVES));
    }
    if (features.contains(ParsingFeature.PAGES) && this.currentPage != null) {
      box.setPage(this.currentPage);
    }

    String trailingChars = this.seekToNextWord(xmlReader, features.contains(ParsingFeature.PAGES));
    if (features.contains(ParsingFeature.TEXT) && !trailingChars.isEmpty()) {
      box.setTrailingChars(trailingChars);
    }

    boolean isHyphenated = false;
    if (box.getText() != null && box.getText().endsWith("\u00ad")) {
      isHyphenated = true;
      String boxText = box.getText();
      box.setText(boxText.substring(0, boxText.length() - 1));
      // Preliminary hyphenation info, dehyphenated form not yet available
      box.setHyphenInfo(true, null);
    } else if (trailingChars.startsWith("\u00ad")) {
      isHyphenated = true;
    }
    if (isHyphenated) {
      box.setTrailingChars(null);
      hyphenEnd = this.readNext(xmlReader, features);
      if (hyphenEnd != null) {
        String dehyphenated = box.getText() + hyphenEnd.getText();
        box.setHyphenInfo(true, dehyphenated);
        hyphenEnd.setHyphenInfo(false, dehyphenated);
      } else {
        // No hyphen end, strip hyphenation info, add trailing hyphen if needed
        if (!box.getText().endsWith("-")
            && (box.getTrailingChars() == null || box.getTrailingChars().endsWith("-"))) {
          box.setTrailingChars("-");
        }
        box.setHyphenInfo(null, null);
      }
    }
    return box;
  }

  private void parseText(
      XMLStreamReader2 xmlReader,
      OcrBox box,
      boolean withHighlights,
      boolean withOffsets,
      boolean withAlternatives)
      throws XMLStreamException {
    org.codehaus.stax2.LocationInfo loc = xmlReader.getLocationInfo();
    if (xmlReader.next() != XMLStreamConstants.CHARACTERS) {
      log.warn(
          "<w> element at line {}, column {} in {} has no text!",
          loc.getStartLocation().getLineNumber(),
          loc.getStartLocation().getColumnNumber(),
          input.getSource().orElse("<unknown>"));
      box.setText(null);
      box.setTextOffset(-1);
      return;
    }
    if (withOffsets) {
      box.setTextOffset(Math.toIntExact(xmlReader.getLocationInfo().getStartingCharOffset()));
    }

    String chars = xmlReader.getText();

    if (withHighlights) {
      box.setHighlightSpan(this.trackHighlightSpan(chars, box));
    }

    if (chars.indexOf(alternativeMarker) < 0) {
      box.setText(chars);
    } else {
      int idx = 0;
      while (idx < chars.length()) {
        int end = chars.indexOf(alternativeMarker, idx);
        if (end < 0) {
          end = chars.length();
        }
        if (idx == 0) {
          box.setText(chars.substring(idx, end));
          if (!withAlternatives) {
            return;
          }
        } else {
          String altText = chars.substring(idx, end);
          box.addAlternative(altText, withOffsets ? box.getTextOffset() + idx : null);
        }
        idx = Math.min(end + 1, chars.length());
      }
    }
  }

  private String seekToNextWord(XMLStreamReader2 xmlReader, boolean trackPages)
      throws XMLStreamException {
    boolean foundWord = false;
    StringBuilder trailingChars = new StringBuilder();
    while (xmlReader.hasNext()) {
      int nextEvent = xmlReader.next();
      if (nextEvent == XMLStreamConstants.START_ELEMENT) {
        String localName = xmlReader.getLocalName();
        if ("w".equals(localName)) {
          foundWord = true;
          break;
        } else if ("l".equals(localName) && trailingChars.lastIndexOf(" ") < 0) {
          trailingChars.append(' ');
        } else if (trackPages && "p".equals(localName)) {
          Dimension dims = null;
          String dimStr = xmlReader.getAttributeValue("", "wh");
          if (dimStr != null && !dimStr.isEmpty()) {
            String[] dimParts = dimStr.split(" ");
            dims = new Dimension(Integer.parseInt(dimParts[0]), Integer.parseInt(dimParts[1]));
          }
          String id = xmlReader.getAttributeValue("http://www.w3.org/XML/1998/namespace", "id");
          if (id == null || id.isEmpty()) {
            id = xmlReader.getAttributeValue("", "pid");
          }
          this.currentPage = new OcrPage(id, dims);
        }
      } else if (nextEvent == XMLStreamConstants.CHARACTERS) {
        String txt = xmlReader.getText();
        boolean isBlank = StringUtils.isBlank(txt);
        if (isBlank
            && (trailingChars.length() == 0
                || trailingChars.lastIndexOf(" ") != (trailingChars.length() - 1))) {
          trailingChars.append(' ');
        } else if (!isBlank) {
          trailingChars.append(txt);
        }
      }
    }
    noMoreWords = !foundWord;
    return trailingChars.toString();
  }
}
