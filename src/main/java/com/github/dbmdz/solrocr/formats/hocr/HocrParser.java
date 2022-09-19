package com.github.dbmdz.solrocr.formats.hocr;

import com.github.dbmdz.solrocr.formats.OcrParser;
import com.github.dbmdz.solrocr.model.OcrBox;
import com.github.dbmdz.solrocr.model.OcrPage;
import java.awt.Dimension;
import java.io.Reader;
import java.lang.invoke.MethodHandles;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import org.apache.commons.lang3.StringUtils;
import org.codehaus.stax2.XMLStreamReader2;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HocrParser extends OcrParser {
  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  private boolean noMoreWords;
  private OcrPage currentPage;
  private OcrBox hyphenEnd = null;

  public HocrParser(Reader input, ParsingFeature... features) throws XMLStreamException {
    super(input, features);
  }

  @Override
  protected OcrBox readNext(XMLStreamReader2 xmlReader, Set<ParsingFeature> features)
      throws XMLStreamException {
    // For hyphenated tokens, we parse  both parts first and then output them one after the other
    if (hyphenEnd != null) {
      OcrBox out = this.hyphenEnd;
      this.hyphenEnd = null;
      return out;
    }

    if (noMoreWords) {
      return null;
    }

    // Advance parser to the next word if necessary
    if (xmlReader.getEventType() != XMLStreamConstants.START_ELEMENT
        || !"span".equals(xmlReader.getLocalName())
        || !"ocrx_word".equals(xmlReader.getAttributeValue("", "class"))) {
      this.seekToNextWord(xmlReader, features.contains(ParsingFeature.PAGES));
    }
    if (xmlReader.getEventType() != XMLStreamConstants.START_ELEMENT) {
      // No words in this document, nothing to do
      return null;
    }

    OcrBox box = new OcrBox();
    Map<String, String> props = parseTitle(xmlReader.getAttributeValue("", "title"));
    if (features.contains(ParsingFeature.TEXT)) {
      this.parseText(
          xmlReader,
          box,
          features.contains(ParsingFeature.HIGHLIGHTS),
          features.contains(ParsingFeature.OFFSETS),
          features.contains(ParsingFeature.ALTERNATIVES));
    }
    if (features.contains(ParsingFeature.COORDINATES)) {
      this.parseCoordinates(box, props.get("bbox"));
    }
    if (features.contains(ParsingFeature.CONFIDENCE) && props.containsKey("x_wconf")) {
      box.setConfidence(Double.parseDouble(props.get("x_wconf")));
    }
    if (features.contains(ParsingFeature.PAGES) && this.currentPage != null) {
      box.setPage(this.currentPage);
    }

    String trailingChars = this.seekToNextWord(xmlReader, features.contains(ParsingFeature.PAGES));
    if (features.contains(ParsingFeature.TEXT) && !trailingChars.isEmpty()) {
      box.setTrailingChars(trailingChars);
    }

    boolean isHyphenated = false;
    if (box.getText() != null && box.getText().replace(END_HL, "").endsWith("\u00ad")) {
      isHyphenated = true;
      String boxText = box.getText();
      box.setText(boxText.replace("\u00ad", ""));
      // Preliminary hyphenation info, no dehyphenated form available yet
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
        // No hyphen end, add hyphen character if needed and strip hyphenation info
        if (box.getText() != null && !box.getText().endsWith("-")) {
          box.setText(box.getText() + "-");
        }
        box.setHyphenInfo(null, null);
      }
    }

    // Boxes without text or coordinates (if either is requested with a feature flag) are ignored
    // since they break things downstream. Skip the current box and continue with next one.
    boolean ignoreBox =
        (features.contains(ParsingFeature.TEXT)
                && (box.getText() == null || box.getText().isEmpty()))
            || (features.contains(ParsingFeature.COORDINATES)
                && (box.getLrx() < 0 && box.getLry() < 0 && box.getUlx() < 0 && box.getUly() < 0));
    if (ignoreBox) {
      box = this.readNext(xmlReader, features);
    }
    return box;
  }

  private Map<String, String> parseTitle(String title) {
    Map<String, String> props = new HashMap<>();
    if (title == null) {
      return props;
    }
    String[] parts = title.split(";");
    for (String part : parts) {
      int spaceIdx = part.indexOf(' ', 3);
      String propertyName = part.substring(0, spaceIdx).trim();
      String propertyValue = part.substring(spaceIdx + 1).trim();
      boolean isQuoted =
          (propertyValue.startsWith("\"") && propertyValue.endsWith("\""))
              || (propertyValue.startsWith("'") && propertyValue.endsWith("'"));
      if (isQuoted) {
        propertyValue = propertyValue.substring(1, propertyValue.length() - 1);
      }
      props.put(propertyName, propertyValue);
    }
    return props;
  }

  private void parseCoordinates(OcrBox box, String bboxStr) {
    String[] parts = bboxStr.split(" ");
    if (parts.length > 0) {
      box.setUlx(Integer.parseInt(parts[0]));
    }
    if (parts.length > 1) {
      box.setUly(Integer.parseInt(parts[1]));
    }
    if (parts.length > 2) {
      box.setLrx(Integer.parseInt(parts[2]));
    }
    if (parts.length > 3) {
      box.setLry(Integer.parseInt(parts[3]));
    } else {
      log.warn("bbox attribute '{}' is incomplete.", bboxStr);
    }
  }

  private void parseText(
      XMLStreamReader2 xmlReader,
      OcrBox box,
      boolean withHighlights,
      boolean withOffsets,
      boolean withAlternatives)
      throws XMLStreamException {
    String txt = null;
    int txtOffset = -1;
    boolean inAlternatives = false;
    while (xmlReader.hasNext()) {
      int nextEvent = xmlReader.next();
      if (nextEvent == XMLStreamConstants.CHARACTERS && txt == null) {
        if (withOffsets) {
          txtOffset = Math.toIntExact(xmlReader.getLocationInfo().getStartingCharOffset());
        }
        txt = xmlReader.getText();
        continue;
      } else if (nextEvent == XMLStreamConstants.END_ELEMENT) {
        if (inAlternatives) {
          inAlternatives = false;
          continue;
        }
        // Ignore empty words
        if (txt == null) {
          return;
        }
        // We assume that we're dealing with valid hOCR, and in this case this is the event for the
        // end of the ocrx_word span, i.e. we have all the text we needed from the box and can
        // terminate and return
        box.setText(txt);
        if (withOffsets) {
          box.setTextOffset(txtOffset);
        }
        if (txt != null && txt.replace(END_HL, "").endsWith("\u00ad")) {
          // Preliminary hyphenation info
          box.setHyphenInfo(true, null);
        }
        // Make sure we don't overwrite highlight spans tracked from alternatives
        if (withHighlights && box.getHighlightSpan() == null) {
          box.setHighlightSpan(this.trackHighlightSpan(txt, box));
        }
        return;
      } else if (nextEvent != XMLStreamConstants.START_ELEMENT) {
        // Nothing of interest
        continue;
      }
      // We're on a START_ELEMENT event now
      String tag = xmlReader.getLocalName();
      if ("span".equals(tag) && "alternatives".equals(xmlReader.getAttributeValue("", "class"))) {
        inAlternatives = true;
        continue;
      }
      if ("ins".equals(tag)) {
        if (xmlReader.next() != XMLStreamConstants.CHARACTERS) {
          throw new IllegalStateException("<ins> elements must have a text node as its sole child");
        }
        if (withOffsets) {
          txtOffset = Math.toIntExact(xmlReader.getLocationInfo().getStartingCharOffset());
        }
        txt = xmlReader.getText();
        if (xmlReader.next() != XMLStreamConstants.END_ELEMENT) {
          throw new IllegalStateException("<ins> elements must have a text node as its sole child");
        }
      } else if (withAlternatives && "del".equals(tag)) {
        if (xmlReader.next() != XMLStreamConstants.CHARACTERS) {
          throw new IllegalStateException("<del> elements must have a text node as its sole child");
        }
        String altText = xmlReader.getText();
        Integer altOffset =
            withOffsets
                ? Math.toIntExact(xmlReader.getLocationInfo().getStartingCharOffset())
                : null;
        if (withHighlights && box.getHighlightSpan() == null) {
          box.setHighlightSpan(this.trackHighlightSpan(altText, box));
        }
        box.addAlternative(altText, altOffset);
        if (xmlReader.next() != XMLStreamConstants.END_ELEMENT) {
          throw new IllegalStateException("<del> elements must have a text node as its sole child");
        }
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
        String hocrClass = xmlReader.getAttributeValue("", "class");
        if ("span".equals(localName) && "ocrx_word".equals(hocrClass)) {
          foundWord = true;
          break;
        } else if ("span".equals(localName)
            && "ocr_line".equals(hocrClass)
            && trailingChars.lastIndexOf(" ") < 0) {
          // Line breaks result in a trailing whitespace character
          trailingChars.append(' ');
        } else if (trackPages && "div".equals(localName) && "ocr_page".equals(hocrClass)) {
          // Page break
          Map<String, String> pageProps = this.parseTitle(xmlReader.getAttributeValue("", "title"));
          Dimension pageDims = null;
          if (pageProps.containsKey("bbox")) {
            String[] bboxParts = pageProps.get("bbox").split(" ");
            pageDims =
                new Dimension(Integer.parseInt(bboxParts[2]), Integer.parseInt(bboxParts[3]));
          }
          String pageId = xmlReader.getAttributeValue("", "id");
          if (pageId == null) {
            pageId = pageProps.get("x_source");
          }
          if (pageId == null) {
            pageId = pageProps.get("ppageno");
          }
          this.currentPage = new OcrPage(pageId, pageDims);
        }
      } else if (nextEvent == XMLStreamConstants.CHARACTERS
          || nextEvent == XMLStreamConstants.SPACE) {
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
