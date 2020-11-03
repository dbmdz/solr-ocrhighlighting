package de.digitalcollections.solrocr.formats.alto;

import de.digitalcollections.solrocr.lucene.filters.BaseOcrCharFilter;
import de.digitalcollections.solrocr.lucene.filters.OcrCharFilterFactory;
import de.digitalcollections.solrocr.reader.PeekingReader;
import de.digitalcollections.solrocr.util.CharBufUtils;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.List;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import org.codehaus.stax2.XMLStreamReader2;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * StAX-based character filter to extract text from ALTO documents while keeping track of the
 * content offsets.
 *
 * This uses the concrete Woodstax implementation, since it supports a multi-document parsing mode
 * that makes our life a lot easier. Also, it comes shipped with Solr, so no need for an external
 * dependency.
 */
public class AltoCharFilter extends BaseOcrCharFilter {
  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  public AltoCharFilter(PeekingReader in, boolean expandAlternatives) {
    super(in, expandAlternatives);
  }

  /** Get the next "word" (i.e. <String /> element) from the ALTO */
  @Override
  protected char[] getNextWord(int outputOffset, boolean expandAlternatives) throws XMLStreamException {
    XMLStreamReader2 xmlReader = getXmlReader();
    while (xmlReader.hasNext()) {
      if (xmlReader.next() != XMLStreamConstants.START_ELEMENT
          || !"String".equals(xmlReader.getLocalName())) {
        continue;
      }
      // Check for hyphenation, we're only interested in the first part of the hyphenated form and
      // use its dehyphenated content instead of the regular content.
      int subsTypeIdx = xmlReader.getAttributeIndex("", "SUBS_TYPE");
      if (subsTypeIdx >= 0 && xmlReader.getAttributeValue(subsTypeIdx).equals("HypPart2")) {
        // We're only interested in the first part of a hyphenated form
        continue;
      }
      String contentAttrib = "CONTENT";
      if (subsTypeIdx >= 0) {
        contentAttrib = "SUBS_CONTENT";
      }

      // Determine the offset at which the content starts and register it in the offset map
      long contentOffset = getAttributeValueOffset(contentAttrib);
      int cumulativeDiff = (int) (contentOffset - outputOffset);
      this.addOffCorrectMap(outputOffset, cumulativeDiff);

      // Read the content into the buffer
      int contentIdx = xmlReader.getAttributeIndex("", contentAttrib);
      String txt = xmlReader.getAttributeValue(contentIdx);

      if (expandAlternatives) {
        List<String> alternatives = new ArrayList<>();
        alternatives.add(txt);
        while (xmlReader.hasNext() && xmlReader.next() != XMLStreamConstants.END_ELEMENT) {
          if (xmlReader.getEventType() != XMLStreamConstants.START_ELEMENT
              || !"ALTERNATIVE".equals(xmlReader.getLocalName())) {
            continue;
          }
          alternatives.add(xmlReader.getElementText());
        }
        if (alternatives.size() > 1) {
          txt = String.join(OcrCharFilterFactory.ALTERNATIVE_MARKER, alternatives);
        }
      }

      // Add trailing space if it's not encoded in the content itself
      if (!txt.endsWith(" ")) {
        txt += " ";
      }
      return txt.toCharArray();
    }
    return null;
  }

  /**
   * Get the character offset of the value for the given attribute in the reader.
   *
   * <strong>Assumes the XMLStreamReader is on a START_ELEMENT event.</strong>
   */
  private long getAttributeValueOffset(String targetAttrib) {
    XMLStreamReader2 xmlReader = getXmlReader();
    PeekingReader peekingReader = getInput();
    if (xmlReader.getEventType() != XMLStreamConstants.START_ELEMENT) {
      throw new IllegalStateException("XMLStreamReader must be on a START_ELEMENT event.");
    }
    char[] backContextBuffer = peekingReader.peekBackContextBuffer();
    int contextLen = peekingReader.getBackContextSize();

    // Place the back-context pointer on the start of the element
    int contextIdx = Math.toIntExact(
        xmlReader.getLocationInfo().getStartingCharOffset()
            - peekingReader.getBackContextStartOffset());

    // Look for the attribute in the back context, starting from the start of
    // the element. Done with character buffers since it's *way* *way* faster than
    // creating a String from the buffer and calling `.indexOf`.
    // Way faster as in doesn't even show up in the sampling profiler anymore.
    char[] needle = (" " + targetAttrib + "=").toCharArray();
    int needleIdx = CharBufUtils.indexOf(backContextBuffer, contextIdx, contextLen, needle);

    if (needleIdx >= 0) {
      // Append 1 to the index to account for the single- or double-quote after the `=`
      return  peekingReader.getBackContextStartOffset() + needleIdx + needle.length + 1;
    }
    return -1;
  }
}
