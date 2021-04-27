package de.digitalcollections.solrocr.formats.alto;

import de.digitalcollections.solrocr.formats.OcrParser;
import de.digitalcollections.solrocr.model.OcrBox;
import de.digitalcollections.solrocr.model.OcrPage;
import de.digitalcollections.solrocr.util.CharBufUtils;
import java.awt.Dimension;
import java.io.Reader;
import java.lang.invoke.MethodHandles;
import java.util.Set;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import org.codehaus.stax2.XMLStreamReader2;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AltoParser extends OcrParser {
  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  private boolean noMoreWords;
  private OcrPage currentPage;
  private Boolean hasExplicitSpaces = null;
  private OcrBox hyphenEnd = null;
  private boolean inHyphenation = false;

  public AltoParser(Reader reader, ParsingFeature... features) throws XMLStreamException {
    super(reader, features);
  }

  @Override
  protected OcrBox readNext(XMLStreamReader2 xmlReader, Set<ParsingFeature> features)
      throws XMLStreamException {
    if (this.hasExplicitSpaces == null) {
      // ALTO can optionally encode explicit spaces with the <SP/> element.
      this.hasExplicitSpaces = this.input.peekBeginning().contains("<SP");
    }

    // If we encounter hyphenated words, we parse both parts before outputting anything and then
    // output them one after the other
    if (hyphenEnd != null) {
      OcrBox out = this.hyphenEnd;
      this.hyphenEnd = null;
      return out;
    }
    if (noMoreWords) {
      return null;
    }

    // Advance reader to the next word if neccessary
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
      }
      if (features.contains(ParsingFeature.HIGHLIGHTS) && box.getHighlightSpan() == null) {
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
      String x = xmlReader.getAttributeValue("", "HPOS");
      String y = xmlReader.getAttributeValue("", "VPOS");
      String width = xmlReader.getAttributeValue("", "WIDTH");
      String height = xmlReader.getAttributeValue("", "HEIGHT");
      if (x != null && !x.isEmpty()) {
        double xNum =  Double.parseDouble(x);
        box.setUlx((int) xNum);
        if (width != null && !width.isEmpty()) {
          box.setLrx((int) xNum + (int) Double.parseDouble(width));
        }
      }
      if (y != null && !y.isEmpty()) {
        double yNum =  Double.parseDouble(y);
        box.setUly((int) yNum);
        if (height != null && !height.isEmpty()) {
          box.setLry((int) yNum + (int) Double.parseDouble(height));
        }
      }
      if (box.getLrx() < 0 || box.getLry() < 0) {
        log.warn(
            "Incomplete coordinates encountered: 'HPOS={}, VPOS={}, WIDTH={}, HEIGHT={}",
            x, y, width, height);
      }
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
    if ((!hasExplicitSpaces || numSpaces > 0)) {
      box.setTrailingChars(" ");
    }

    // Hyphenation handling
    if (box.isHyphenStart()) {
      if (this.inHyphenation) {
        // Two subsequent hyphen starts, broken/invalid data, but we try to deal with it anyway
        // by not looking for a hyphen end for every subsequent hyphen start
        return box;
      }
      this.inHyphenation = true;
      this.hyphenEnd = this.readNext(xmlReader, features);
      if (this.hyphenEnd != null && this.hyphenEnd.isHyphenated() && !this.hyphenEnd.isHyphenStart()) {
        // Insert highlighting markers at correct positions in the dehyphenated content
        // This is assuming that both the end is fully part of the dehyphenated form.
        boolean modified = false;
        StringBuilder dehyphenated = new StringBuilder(hyphenEnd.getDehyphenatedForm());
        if (box.getText().contains(START_HL)) {
          dehyphenated.insert(box.getText().indexOf(START_HL), START_HL);
          modified = true;
        }
        if (box.getText().contains(END_HL)) {
          dehyphenated.insert(box.getText().indexOf(END_HL), END_HL);
          modified = true;
        }
        int endIdx = dehyphenated.indexOf(hyphenEnd.getText().replace(END_HL, "").replace(START_HL, ""));
        if (hyphenEnd.getText().contains(START_HL) && endIdx >= 0) {
          dehyphenated.insert(endIdx + hyphenEnd.getText().indexOf(START_HL), START_HL);
          modified = true;
        }
        if (hyphenEnd.getText().contains(END_HL) && endIdx >= 0) {
          dehyphenated.insert(endIdx + hyphenEnd.getText().indexOf(END_HL), END_HL);
          modified = true;
        }
        if (modified) {
          box.setHyphenInfo(true, dehyphenated.toString());
          hyphenEnd.setHyphenInfo(false, dehyphenated.toString());
        }
        // Full hyphenation, no whitespace between start and end
        box.setTrailingChars("");
      } else {
        box.setHyphenInfo(null, null);
        box.setDehyphenatedOffset(null);
      }
      this.inHyphenation = false;
    }

    // Boxes without text or coordinates (if either is requested with a feature flag) are ignored since they break
    // things downstream
    boolean ignoreBox =
        (features.contains(ParsingFeature.TEXT) && (box.getText() == null || box.getText().isEmpty()))
        || (features.contains(ParsingFeature.COORDINATES)
            && (box.getLrx() < 0 && box.getLry() < 0 && box.getUlx() < 0 && box.getUly() < 0));
    if (ignoreBox) {
      return null;
    }
    return box;
  }

  /** Advance parser to the next word, counting the number of pages encountered along the way, as
   *  well as page breaks, if desired.
   */
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
