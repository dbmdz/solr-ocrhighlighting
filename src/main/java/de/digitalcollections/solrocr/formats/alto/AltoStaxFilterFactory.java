package de.digitalcollections.solrocr.formats.alto;

import com.ctc.wstx.api.WstxInputProperties;
import com.ctc.wstx.exc.WstxEOFException;
import com.ctc.wstx.stax.WstxInputFactory;
import java.io.IOException;
import java.io.Reader;
import java.util.HashMap;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import org.apache.lucene.analysis.charfilter.BaseCharFilter;
import org.apache.lucene.analysis.util.CharFilterFactory;
import org.codehaus.stax2.XMLStreamReader2;

public class AltoStaxFilterFactory extends CharFilterFactory {
  protected AltoStaxFilterFactory() {
    super(new HashMap<>());
  }

  @Override
  public Reader create(Reader input) {
    return new AltoStaxFilter(input);
  }

  public static class AltoStaxFilter extends BaseCharFilter {
    private static final WstxInputFactory xmlInputFactory = new WstxInputFactory();

    private final XMLStreamReader2 xmlReader;
    private boolean finished = false;
    private int outputOffset = 0;
    private char[] curWord = null;
    private int curWordIdx = -1;

    public AltoStaxFilter(Reader in) {
      super(in);
      xmlInputFactory.getConfig()
          .setInputParsingMode(WstxInputProperties.PARSING_MODE_DOCUMENTS);
      try {
        this.xmlReader = (XMLStreamReader2) xmlInputFactory.createXMLStreamReader(in);
      } catch (XMLStreamException e) {
        throw new RuntimeException(e);
      }
    }

    private boolean isFinished() {
      return finished && curWordIdx == curWord.length;
    }

    private void readNextWord() throws XMLStreamException {
      while (xmlReader.hasNext()) {
        try {
          if (xmlReader.next() != XMLStreamConstants.START_ELEMENT
              || !"String".equals(xmlReader.getLocalName())) {
            continue;
          }
        } catch (WstxEOFException e) {
          // We're really at the end of the input, finalize
          break;
        }
        int subsTypeIdx = xmlReader.getAttributeIndex("", "SUBS_TYPE");
        if (subsTypeIdx >= 0 && xmlReader.getAttributeValue(subsTypeIdx).equals("HypPart2")) {
          // We're only interested in the first part of a hyphenated form
          continue;
        }
        String contentAttrib = "CONTENT";
        if (subsTypeIdx >= 0) {
          contentAttrib = "SUBS_CONTENT";
        }
        long contentOffset = getAttributeValueOffset(contentAttrib);
        int cumulativeDiff = (int) (contentOffset - outputOffset);
        this.addOffCorrectMap(outputOffset, cumulativeDiff);
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
      // Is the reader closed?
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

    /** Get the character offset of the value for the given attribute in the reader.
     *
     * Assumes the XMLStreamReader is on a START_ELEMENT event.
     * Assumes the XML element is using minimal whitespace and is situated on a single line.
     */
    private long getAttributeValueOffset(String targetAttrib) {
      long idx = xmlReader.getLocationInfo().getStartingCharOffset();
      idx += 1; // '<'
      String prefix = xmlReader.getPrefix();
      if (prefix != null && prefix.length() > 0) {
        idx += prefix.length();
        idx += 1; // ':'
      }
      idx += xmlReader.getLocalName().length(); // 'tagname'

      // Any namespaces?
      int nsCount = xmlReader.getNamespaceCount();
      if (nsCount > 0) {
        for (int i = 0; i < nsCount; ++i) {
          idx += 7; // ' xmlns:'
          idx += xmlReader.getNamespacePrefix(i).length();
          idx += 2; // '="'
          idx += xmlReader.getNamespaceURI().length();
          idx += 1; // '"'
        }
      }

      // How about attrs?
      int attrCount = xmlReader.getAttributeCount();
      if (attrCount > 0) {
        for (int i = 0;  i < attrCount; ++i) {
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
      return -1;
    }
  }
}
