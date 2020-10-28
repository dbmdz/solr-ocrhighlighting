package de.digitalcollections.solrocr.formats.alto;

import com.ctc.wstx.api.WstxInputProperties;
import com.ctc.wstx.exc.WstxEOFException;
import com.ctc.wstx.stax.WstxInputFactory;
import java.io.IOException;
import java.io.Reader;
import java.io.StringWriter;
import java.util.HashMap;
import javax.xml.namespace.QName;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.Attribute;
import javax.xml.stream.events.StartElement;
import javax.xml.stream.events.XMLEvent;
import org.apache.lucene.analysis.charfilter.BaseCharFilter;
import org.apache.lucene.analysis.util.CharFilterFactory;
import org.codehaus.stax2.XMLEventReader2;
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
    private static final WstxInputFactory xmlInputFactory = (WstxInputFactory) WstxInputFactory.newFactory();

    private XMLStreamReader2 xmlReader;
    private XMLEventReader2 xmlEventReader;
    private boolean finished = false;
    private int outputOffset = 0;
    private char[] curWord = null;
    private int curWordIdx = -1;

    public AltoStaxFilter(Reader in) {
      super(in);
      xmlInputFactory.getConfig().setInputParsingMode(WstxInputProperties.PARSING_MODE_DOCUMENTS);
      try {
        //this.xmlEventReader = (XMLEventReader2) xmlInputFactory.createXMLEventReader(in);
        this.xmlReader = (XMLStreamReader2) xmlInputFactory.createXMLStreamReader(in);
      } catch (XMLStreamException e) {
        throw new RuntimeException(e);
      }
    }

    private boolean isFinished() {
      return finished && curWordIdx == curWord.length;
    }

    private void readNextWordEvt() throws XMLStreamException {
      while (xmlEventReader.hasNextEvent()) {
        XMLEvent event;
        try {
          event = xmlEventReader.nextEvent();
          if (!event.isStartElement() || !"String".equals(event.asStartElement().getName().getLocalPart())) {
            continue;
          }
        } catch (WstxEOFException e) {
          // We're really at the end of the input, finalize
          break;
        }
        StartElement elem = event.asStartElement();
        Attribute subsType = elem.getAttributeByName(new QName("SUBS_TYPE"));
        if (subsType != null && subsType.getValue().equals("HypPart2")) {
          // We don't care about the latter part of a hyphenation, continue to next element
          continue;
        }
        long inputOffset = event.getLocation().getCharacterOffset();
        StringWriter w = new StringWriter(1024);
        event.writeAsEncodedUnicode(w);
        String elementText = w.toString();

        String contentAttrib;
        if (subsType != null && subsType.getValue().equals("HypPart1")) {
          contentAttrib = "SUBS_CONTENT";
        } else {
          contentAttrib = "CONTENT";
        }
        String txt = elem.getAttributeByName(new QName(contentAttrib)).getValue();
        inputOffset += (elementText.indexOf(contentAttrib) + contentAttrib.length() + 2);
        int cumulativeDiff = (int) (inputOffset - outputOffset);
        this.addOffCorrectMap(outputOffset, cumulativeDiff);
        if (!txt.endsWith(" ")) {
          txt += " ";
        }
        curWord = txt.toCharArray();
        curWordIdx = 0;
        return;
      }
      this.finished = true;
    }

    private void readNextWordStream() throws XMLStreamException {
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
        // TODO: Handle hyphenated words!
        int subsTypeIdx = xmlReader.getAttributeIndex("", "SUBS_TYPE");
        if (subsTypeIdx >= 0 && xmlReader.getAttributeValue(subsTypeIdx).equals("HypPart2")) {
          // We're only interested in the first part of a hyphenated form
          continue;
        }
        String contentAttrib = "CONTENT";
        if (subsTypeIdx >= 0) {
          contentAttrib = "SUBS_CONTENT";
        }
        long inputOffset = xmlReader.getLocationInfo().getStartingCharOffset();
        inputOffset += (
            getSelfClosingElementString().indexOf(contentAttrib)
                + contentAttrib.length() + 2);
        int cumulativeDiff = (int) (inputOffset - outputOffset);
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

    /**
     * Reads characters into a portion of an array.  This method will block
     * until some input is available, an I/O error occurs, or the end of the
     * stream is reached.
     *
     * @param      cbuf  Destination buffer
     * @param      off   Offset at which to start storing characters
     * @param      len   Maximum number of characters to read
     *
     * @return     The number of characters read, or -1 if the end of the
     *             stream has been reached
     *
     * @exception  IOException  If an I/O error occurs
     * @exception  IndexOutOfBoundsException
     *             If {@code off} is negative, or {@code len} is negative,
     *             or {@code len} is greater than {@code cbuf.length - off}
     */
    @Override
    public int read(char[] cbuf, int off, int len) throws IOException {
      // Is the reader closed?
      if (this.isFinished()) {
        return -1;
      }
      if (this.curWord == null) {
        try {
          this.readNextWordStream();
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
            this.readNextWordStream();
          } catch (XMLStreamException e) {
            throw new IOException(e);
          }
        }
      }
      return numRead;
    }

    /** Get the XML string representation of the current element.
     *
     * Assumes the XMLStreamReader is on a START_ELEMENT event.
     * Assumes the XML is using minimal whitespace and is situated on a single line.
     */
    private String getSelfClosingElementString() {
      StringBuilder sb = new StringBuilder();
      sb.append('<');
      String prefix = xmlReader.getPrefix();
      if (prefix != null && prefix.length() > 0) {
        sb.append(prefix);
        sb.append(':');
      }
      sb.append(xmlReader.getLocalName());

      // Any namespaces?
      int nsCount = xmlReader.getNamespaceCount();
      if (nsCount > 0) {
        for (int i = 0; i < nsCount; ++i) {
          sb.append(" xmlns:");
          sb.append(xmlReader.getNamespacePrefix(i));
          sb.append("=\"");
          sb.append(xmlReader.getNamespaceURI());
          sb.append('"');
        }
      }

      int attrCount = xmlReader.getAttributeCount();

      // How about attrs?
      if (attrCount > 0) {
        for (int i = 0;  i < attrCount; ++i) {
          sb.append(' ');
          sb.append(xmlReader.getAttributeName(i).toString());
          sb.append("=\"");
          sb.append(xmlReader.getAttributeValue(i));
          sb.append('"');
        }
      }

      sb.append("/>");
      return sb.toString();
    }
  }
}
