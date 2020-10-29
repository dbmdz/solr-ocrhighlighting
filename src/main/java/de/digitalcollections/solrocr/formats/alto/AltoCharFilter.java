package de.digitalcollections.solrocr.formats.alto;

import com.ctc.wstx.api.WstxInputProperties;
import com.ctc.wstx.exc.WstxEOFException;
import com.ctc.wstx.stax.WstxInputFactory;
import java.io.IOException;
import java.io.Reader;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import org.apache.lucene.analysis.charfilter.BaseCharFilter;
import org.codehaus.stax2.XMLStreamReader2;

/**
 * StAX-based character filter to extract text from ALTO documents while keeping track of the
 * content offsets.
 *
 * This uses the concrete Woodstax implementation, since it supports a multi-document parsing mode
 * that makes our life a lot easier. Also, it comes shipped with Solr, so no need for an external
 * dependency.
 */
public class AltoCharFilter extends BaseCharFilter {

  private static final WstxInputFactory xmlInputFactory = new WstxInputFactory();

  private final XMLStreamReader2 xmlReader;
  private boolean finished = false;
  private int outputOffset = 0;
  private char[] curWord = null;
  private int curWordIdx = -1;

  public AltoCharFilter(Reader in) {
    super(in);
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
   * <ul>
   *   <li>Assumes the XMLStreamReader is on a START_ELEMENT event.</li>
   *   <li>Assumes the XML element is using minimal whitespace and is situated on a single line.</li>
   * </ul>
   */
  private long getAttributeValueOffset(String targetAttrib) {
    long idx = xmlReader.getLocationInfo().getStartingCharOffset();

    // Element opening with optional namespace prefix
    idx += 1; // '<'
    String prefix = xmlReader.getPrefix();
    if (prefix != null && prefix.length() > 0) {
      idx += prefix.length();
      idx += 1; // ':'
    }
    idx += xmlReader.getLocalName().length(); // 'tagname'

    // Optional namespace declarations
    int numNamespaces = xmlReader.getNamespaceCount();
    if (numNamespaces > 0) {
      for (int i = 0; i < numNamespaces; ++i) {
        idx += 7; // ' xmlns:'
        idx += xmlReader.getNamespacePrefix(i).length();
        idx += 2; // '="'
        idx += xmlReader.getNamespaceURI().length();
        idx += 1; // '"'
      }
    }

    // Attributes
    int numAttrs = xmlReader.getAttributeCount();
    if (numAttrs > 0) {
      for (int i = 0; i < numAttrs; ++i) {
        idx += 1; // ' '
        String attribName = xmlReader.getAttributeName(i).toString();
        idx += attribName.length();
        idx += 2; // '="'
        if (targetAttrib.equals(attribName)) {
          return idx;
        }
        idx += xmlReader.getAttributeValue(i).length();
        idx += 1; // '"'
      }
    }

    // Attibute not found
    return -1;
  }
}
