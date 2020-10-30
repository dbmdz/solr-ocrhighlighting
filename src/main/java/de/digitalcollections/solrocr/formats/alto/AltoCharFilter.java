package de.digitalcollections.solrocr.formats.alto;

import com.ctc.wstx.api.WstxInputProperties;
import com.ctc.wstx.exc.WstxEOFException;
import com.ctc.wstx.stax.WstxInputFactory;
import de.digitalcollections.solrocr.reader.PeekingReader;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import org.apache.lucene.analysis.charfilter.BaseCharFilter;
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
public class AltoCharFilter extends BaseCharFilter {
  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
  private static final WstxInputFactory xmlInputFactory = new WstxInputFactory();

  private final PeekingReader peekingReader;
  private final XMLStreamReader2 xmlReader;
  private boolean finished = false;
  private int outputOffset = 0;
  private char[] curWord = null;
  private int curWordIdx = -1;

  public AltoCharFilter(PeekingReader in) {
    super(in);
    // Buffers smaller than 8192 chars caused problems during testing, so warn users
    if (in.getMaxBackContextSize() < 8192) {
      log.warn("Back-Context size of input peeking reader is smaller than 8192 chars, might lead to problems!");
    }
    this.peekingReader = in;
    // Our input can be multiple concatenated documents, or also individual chunks from one or more
    // documents. With this parsing mode we can treat them as sequential START_ELEMENT events and
    // just ignore the fact that they're technically different documents.
    xmlInputFactory.getConfig()
        .setInputParsingMode(WstxInputProperties.PARSING_MODE_DOCUMENTS);
    try {
      this.xmlReader = (XMLStreamReader2) xmlInputFactory.createXMLStreamReader(in);
    } catch (XMLStreamException e) {
      throw new RuntimeException(e);
    }
  }

  /** Have completely finished reading from the underlying reader and our last buffered word? */
  private boolean isFinished() {
    return finished && curWordIdx == curWord.length;
  }

  /** Buffer the next "word" (i.e. <String /> element) from the ALTO */
  private void readNextWord() throws XMLStreamException {
    while (xmlReader.hasNext()) {
      try {
        if (xmlReader.next() != XMLStreamConstants.START_ELEMENT
            || !"String".equals(xmlReader.getLocalName())) {
          continue;
        }
      } catch (WstxEOFException e) {
        // Since we can't rely on the END_DOCUMENT event for checking if we're done (due to the
        // multi-document parsing mode), we just catch the EOF exception and then finalize.
        break;
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
      if (!txt.endsWith(" ")) {
        txt += " ";
      }
      curWord = txt.toCharArray();
      curWordIdx = 0;
      return;
    }
    this.finished = true;
  }

  @Override
  public int read(char[] cbuf, int off, int len) throws IOException {
    if (this.isFinished()) {
      return -1;
    }

    if (this.curWord == null) {
      try {
        this.readNextWord();
      } catch (XMLStreamException e) {
        throw new IOException(e);
      }
    }

    int numRead = 0;
    while (numRead < len && !this.isFinished()) {
      int lenToRead = Math.min(len - numRead, this.curWord.length - this.curWordIdx);
      System.arraycopy(this.curWord, this.curWordIdx, cbuf, off + numRead, lenToRead);
      curWordIdx += lenToRead;
      outputOffset += lenToRead;
      numRead += lenToRead;
      if (curWordIdx == curWord.length) {
        try {
          this.readNextWord();
        } catch (XMLStreamException e) {
          throw new IOException(e);
        }
      }
    }
    return numRead;
  }

  /**
   * Get the character offset of the value for the given attribute in the reader.
   *
   * <strong>Assumes the XMLStreamReader is on a START_ELEMENT event.</strong>
   */
  private long getAttributeValueOffset(String targetAttrib) {
    if (xmlReader.getEventType() != XMLStreamConstants.START_ELEMENT) {
      throw new IllegalStateException("XMLStreamReader must be on a START_ELEMENT event.");
    }
    long idx = xmlReader.getLocationInfo().getStartingCharOffset();
    long contextIdx = idx - peekingReader.getBackContextStartOffset();
    String backContext = peekingReader.peekBackContext();
    String elementText = backContext
        .substring((int) contextIdx, backContext.indexOf(">", (int) contextIdx) + 1)
        .replace("\\s", " ");  // Normalize whitespace
    int attribIdx = elementText.indexOf(" " + targetAttrib + "=");
    if (attribIdx < 0) {
      return -1;
    }
    return  idx + attribIdx + targetAttrib.length() + 3;
  }
}
