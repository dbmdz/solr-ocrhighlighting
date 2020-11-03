package de.digitalcollections.solrocr.formats.hocr;

import com.ctc.wstx.exc.WstxEOFException;
import de.digitalcollections.solrocr.lucene.filters.BaseOcrCharFilter;
import de.digitalcollections.solrocr.lucene.filters.OcrCharFilterFactory;
import de.digitalcollections.solrocr.reader.PeekingReader;
import de.digitalcollections.solrocr.util.CharBufUtils;
import java.lang.invoke.MethodHandles;
import javax.xml.stream.XMLStreamException;
import org.codehaus.stax2.XMLStreamReader2;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static javax.xml.stream.XMLStreamConstants.*;

public class HocrCharFilter extends BaseOcrCharFilter {
  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  private boolean inLine = false;

  public HocrCharFilter(PeekingReader in, boolean expandAlternatives) {
    super(in, expandAlternatives);
  }

  @Override
  protected char[] getNextWord(int outputOffset, boolean expandAlternatives)
      throws XMLStreamException {
    XMLStreamReader2 xmlReader = getXmlReader();
    StringBuilder txt = new StringBuilder(64);
    boolean inWord = false;
    boolean inAlternatives = false;
    boolean inIns = false;
    boolean inDel = false;

    while (xmlReader.hasNext()) {
      int evt;
      try {
        evt = xmlReader.next();
      } catch (WstxEOFException e) {
        break;
      }

      // We ignore everything until we're inside of a line
      //if (!inLine && (evt != START_ELEMENT  || !"ocr_line".equals(xmlReader.getAttributeValue("", "class")))) {
      //  continue;
      //}

      if (evt == CHARACTERS) {
        // The only characters inside of a line that are not considered part of the plaintext
        // are those inside of alternative spans, but outside of <del> elements.
        if (!inLine) {
          continue;
        }
        if (inAlternatives) {
          if (!inDel && !inIns) {
            continue;
          }
          if (inDel && !expandAlternatives) {
            continue;
          }
        }

        long startOffset = xmlReader.getLocationInfo().getStartingCharOffset();
        char[] textBuf = xmlReader.getTextCharacters();
        int textStart = xmlReader.getTextStart();
        int textLength = xmlReader.getTextLength();

        if (textBuf[textStart] == '\u00ad') {
          // hOCR marks hyphenation with a soft hyphen character, to dehyphenate we just strip it.
          // Also, due to the way we're parsing the OCR, the soft hyphen character will always be
          // the first character in a character node (following a word).
          textStart += 1;
          textLength -= 1;
        }

        if (textLength == 0) {
          continue;
        }

        if (textLength > 1 && CharBufUtils.isWhitespace(textBuf, textStart, textLength)) {
          // Normalize whitespace to a single char
          startOffset += textLength - 1;
          textStart = textStart + textLength - 1;
          textLength = 1;
        }
        txt.append(textBuf, textStart, textLength);
        this.addOffCorrectMap(outputOffset, (int) (startOffset - outputOffset));
        outputOffset += textLength;
        continue;
      }

      if (evt == START_ELEMENT) {
        String type = xmlReader.getAttributeValue("", "class");
        String tag = xmlReader.getLocalName();
        if ("ocr_line".equals(type)) {
          inLine = true;
        } else if ("ocrx_word".equals(type)) {
          inWord = true;
        } else if ("alternatives".equals(type)) {
          inAlternatives = true;
        } else if ("ins".equals(tag)) {
          inIns = true;
        } else if ("del".equals(tag)) {
          inDel = true;
        }
        if (expandAlternatives && inDel) {
          // Every alternative needs to be prefixed by the alternative marker
          txt.append(OcrCharFilterFactory.ALTERNATIVE_MARKER);
          outputOffset += OcrCharFilterFactory.ALTERNATIVE_MARKER.length();
        }
      } else if (evt == END_ELEMENT) {
        String tag = xmlReader.getLocalName();
        if ("ins".equals(tag)) {
          inIns = false;
        } else if ("del".equals(tag)) {
          inDel = false;
        } else if (inAlternatives && "span".equals(tag)) {
          inAlternatives = false;
        } else if (inWord) {
          return txt.toString().toCharArray();
        } else if (this.inLine) {
          this.inLine = false;
        } else {
          if ("p".equals(tag) || "div".equals(tag)) {
            txt.append("\n");
            outputOffset += 1;
          }
        }
      }
    }
    return null;
  }
}
