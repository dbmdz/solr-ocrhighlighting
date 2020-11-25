package de.digitalcollections.solrocr.formats.alto;

import de.digitalcollections.solrocr.formats.OcrParser;
import de.digitalcollections.solrocr.model.OcrBox;
import de.digitalcollections.solrocr.model.OcrPage;
import de.digitalcollections.solrocr.util.CharBufUtils;
import java.awt.Dimension;
import java.io.Reader;
import java.util.Set;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import org.codehaus.stax2.XMLStreamReader2;

public class AltoParser extends OcrParser {
  private boolean noMoreWords;
  private OcrPage currentPage;
  private boolean hasExplicitSpaces = false;

  public AltoParser(Reader reader, ParsingFeature... features) throws XMLStreamException {
    super(reader, features);
  }

  @Override
  protected OcrBox readNext(XMLStreamReader2 xmlReader, Set<ParsingFeature> features)
      throws XMLStreamException {
    if (noMoreWords) {
      return null;
    }

    if (xmlReader.getEventType() != XMLStreamConstants.START_ELEMENT
        || !"String".equals(xmlReader.getLocalName())) {
      this.seekToNextWord(xmlReader, features.contains(ParsingFeature.PAGES));
    }

    OcrBox box = new OcrBox();

    // Parse text
    if (features.contains(ParsingFeature.TEXT)) {
      String text = xmlReader.getAttributeValue("", "CONTENT");

      String subsType = xmlReader.getAttributeValue("", "SUBS_TYPE");
      Boolean hyphenStart = subsType == null ? null : "HypPart1".equals(subsType);
      if (hyphenStart != null && hyphenStart) {
        text += "-";
      }
      box.setText(text);
      if (hyphenStart != null) {
        String dehyphenated = xmlReader.getAttributeValue("", "SUBS_CONTENT");
        box.setHyphenInfo(hyphenStart, dehyphenated);
        if (features.contains(ParsingFeature.HIGHLIGHTS) && box.getHighlightSpan() == null) {
          box.setHighlightSpan(this.trackHighlightSpan(dehyphenated, box));
        }
      } else if (features.contains(ParsingFeature.HIGHLIGHTS) && box.getHighlightSpan() == null) {
        box.setHighlightSpan(this.trackHighlightSpan(text, box));
      }
      if (features.contains(ParsingFeature.OFFSETS)) {
        box.setTextOffset(Math.toIntExact(getAttributeValueOffset("CONTENT", xmlReader)));
        if (hyphenStart != null) {
          box.setDehyphenatedOffset(Math.toIntExact(getAttributeValueOffset("SUBS_CONTENT", xmlReader)));
        }
      }
    }

    // Parse coordinates
    if (features.contains(ParsingFeature.COORDINATES)) {
      int x = (int) Double.parseDouble(xmlReader.getAttributeValue("", "HPOS"));
      int y = (int) Double.parseDouble(xmlReader.getAttributeValue("", "VPOS"));
      int w = (int) Double.parseDouble(xmlReader.getAttributeValue("", "WIDTH"));
      int h = (int) Double.parseDouble(xmlReader.getAttributeValue("", "HEIGHT"));
      box.setUlx(x);
      box.setUly(y);
      box.setLrx(x + w);
      box.setLry(y + h);
    }

    if (features.contains(ParsingFeature.CONFIDENCE)) {
      String wc = xmlReader.getAttributeValue("", "WC");
      if (wc != null && !wc.isEmpty()) {
        box.setConfidence(Double.parseDouble(wc));
      }
    }

    // Parse alternatives
    if (features.contains(ParsingFeature.ALTERNATIVES)) {
      while (xmlReader.hasNext() && xmlReader.next() != XMLStreamConstants.END_ELEMENT) {
        if (xmlReader.getEventType() != XMLStreamConstants.START_ELEMENT
            || !"ALTERNATIVE".equals(xmlReader.getLocalName())) {
          continue;
        }
        int evt = xmlReader.next();
        if (evt != XMLStreamConstants.CHARACTERS) {
          throw new IllegalStateException("An ALTERNATIVE element can only have a text node as its sole child");
        }
        Long offset = features.contains(ParsingFeature.OFFSETS)
            ? xmlReader.getLocationInfo().getStartingCharOffset() : null;
        String alternative = xmlReader.getText();
        box.addAlternative(alternative, offset != null
            ? Math.toIntExact(offset)
            : null);
        if (features.contains(ParsingFeature.HIGHLIGHTS) && box.getHighlightSpan() == null) {
          box.setHighlightSpan(this.trackHighlightSpan(alternative, box));
        }
        if (xmlReader.next() != XMLStreamConstants.END_ELEMENT) {
          throw new IllegalStateException("An ALTERNATIVE element can only have a text node as its sole child");
        }
      }
    }

    if (features.contains(ParsingFeature.PAGES) && this.currentPage != null) {
      box.setPage(this.currentPage);
    }

    // Trailing spaces, if encoded explicitly
    int numSpaces = this.seekToNextWord(xmlReader, features.contains(ParsingFeature.PAGES));
    if ((!hasExplicitSpaces || numSpaces > 0)  && !box.isHyphenStart()) {
      box.setTrailingChars(" ");
    }

    return box;
  }

  private int seekToNextWord(XMLStreamReader2 xmlReader, boolean trackPages) throws XMLStreamException {
    int numSpaces = 0;
    boolean foundWord = false;
    while (xmlReader.hasNext()) {
      if (xmlReader.next() != XMLStreamConstants.START_ELEMENT) {
        continue;
      }
      String localName = xmlReader.getLocalName();
      if ("String".equals(localName)) {
        foundWord = true;
        break;
      } else if ("SP".equals(localName)) {
        this.hasExplicitSpaces = true;
        numSpaces++;
      } else if ("TextLine".equals(localName)) {
        numSpaces++;
      } else if ("Page".equals(localName) && trackPages) {
        String width = xmlReader.getAttributeValue("", "WIDTH");
        String height = xmlReader.getAttributeValue("", "HEIGHT");
        Dimension dims = null;
        if (width != null && height != null) {
          try {
            dims = new Dimension((int) Double.parseDouble(width), (int) Double.parseDouble(height));
          } catch (NumberFormatException e) {
            // NOP, we're only interested in integer dimensions
          }
        }
        this.currentPage = new OcrPage(xmlReader.getAttributeValue("", "ID"), dims);
      }
    }
    noMoreWords = !foundWord;
    return numSpaces;
  }

  /**
   * Get the character offset of the value for the given attribute in the reader.
   *
   * <strong>Assumes the XMLStreamReader is on a START_ELEMENT event.</strong>
   */
  private long getAttributeValueOffset(String targetAttrib, XMLStreamReader2 xmlReader) {
    if (xmlReader.getEventType() != XMLStreamConstants.START_ELEMENT) {
      throw new IllegalStateException("XMLStreamReader must be on a START_ELEMENT event.");
    }
    char[] backContextBuffer = input.peekBackContextBuffer();
    int contextLen = input.getBackContextSize();

    // Place the back-context pointer on the start of the element
    int contextIdx = Math.toIntExact(
        xmlReader.getLocationInfo().getStartingCharOffset()
            - input.getBackContextStartOffset());

    // Look for the attribute in the back context, starting from the start of
    // the element. Done with character buffers since it's *way* *way* faster than
    // creating a String from the buffer and calling `.indexOf`.
    // Way faster as in doesn't even show up in the sampling profiler anymore.
    char[] needle = (" " + targetAttrib + "=").toCharArray();
    int needleIdx = CharBufUtils.indexOf(backContextBuffer, contextIdx, contextLen, needle);

    if (needleIdx >= 0) {
      // Append 1 to the index to account for the single- or double-quote after the `=`
      return  input.getBackContextStartOffset() + needleIdx + needle.length + 1;
    }
    return -1;
  }
}
