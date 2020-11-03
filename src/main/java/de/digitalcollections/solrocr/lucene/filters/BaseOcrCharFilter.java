package de.digitalcollections.solrocr.lucene.filters;

import com.ctc.wstx.api.WstxInputProperties;
import com.ctc.wstx.exc.WstxEOFException;
import com.ctc.wstx.stax.WstxInputFactory;
import de.digitalcollections.solrocr.formats.alto.AltoCharFilter;
import de.digitalcollections.solrocr.formats.hocr.HocrCharFilter;
import de.digitalcollections.solrocr.reader.PeekingReader;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import javax.xml.stream.XMLStreamException;
import org.apache.lucene.analysis.CharFilter;
import org.apache.lucene.analysis.charfilter.BaseCharFilter;
import org.codehaus.stax2.XMLStreamReader2;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Base class for {@link CharFilter} implementations that extract text from OCR markup while keeping
 *  track of the offsets in the input.
 *
 * <p>This uses the concrete Woodstax implementation, since it supports a multi-document parsing mode
 * that makes our life a lot easier. Also, it comes shipped with Solr, so no need for an external
 * dependency.
 *
 * <p>The {@link #getNextWord(int, boolean)} method is a bit tricky to get right, implementers should
 * take inspiration from the existing implementations in {@link AltoCharFilter} and {@link HocrCharFilter}.
 */
public abstract class BaseOcrCharFilter extends BaseCharFilter {
  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
  private static final WstxInputFactory xmlInputFactory = new WstxInputFactory();

  private final PeekingReader peekingReader;
  private final XMLStreamReader2 xmlReader;
  private final boolean expandAlternatives;

  private boolean finished = false;
  private int outputOffset = 0;
  private char[] curWord = null;
  private int curWordIdx = -1;

  public BaseOcrCharFilter(PeekingReader in, boolean expandAlternatives) {
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
    xmlInputFactory.getConfig()
        .doSupportDTDs(false);
    try {
      this.xmlReader = (XMLStreamReader2) xmlInputFactory.createXMLStreamReader(in);
    } catch (XMLStreamException e) {
      throw new RuntimeException(e);
    }
    this.expandAlternatives = expandAlternatives;
  }

  /**
   * Read the next word from the OCR file and update the offset map.
   *
   * <p>Implementers should advance the reader obtained from {@link #getXmlReader()} until the next
   * "word" in the OCR. They should then update the offset map with {@link #addOffCorrectMap(int, int)}
   * and return the "word" as a character buffer.
   *
   * <p>When {@code expandAlternatives} is {@code true}, implementers should encode alternative readings
   * for each word into the output buffer, with each alternative being separated by
   * {@link OcrCharFilterFactory#ALTERNATIVE_MARKER} {@code "\u2060\u0260} from its neighbor.
   */
  protected abstract char[] getNextWord(int outputOffset, boolean expandAlternatives) throws XMLStreamException;

  /** Have we completely finished reading from the underlying reader and our last buffered word? */
  private boolean isFinished() {
    return finished && (curWord == null || curWordIdx == curWord.length);
  }

  /** Get the underlying input reader with the ability to see the back context.
   *
   * This can be useful if the OCR text is inside of attributes and you need access to the raw
   * markup to calculate the offset, since this is something that
   * {@link XMLStreamReader2#getLocationInfo()} does not provide.
   */
  protected PeekingReader getInput() {
    return this.peekingReader;
  }

  /** Get the underlying StAX-based XML reader. */
  protected XMLStreamReader2 getXmlReader() {
    return this.xmlReader;
  }

  private void readNextWord() throws IOException {
    try {
      this.curWord = this.getNextWord(outputOffset, expandAlternatives);
      if (this.curWord == null) {
        this.finished = true;
      }
      this.curWordIdx = 0;
    } catch (WstxEOFException e) {
      // Since we can't rely on the END_DOCUMENT event for checking if we're done (due to the
      // multi-document parsing mode), we just catch the EOF exception and then finalize.
      this.finished = true;
    } catch (XMLStreamException e) {
      throw new IOException(e);
    }
  }

  @Override
  public int read(char[] cbuf, int off, int len) throws IOException {
    if (this.isFinished()) {
      return -1;
    }

    if (this.curWord == null) {
      this.readNextWord();
    }

    int numRead = 0;
    while (numRead < len && !this.isFinished()) {
      int lenToRead = Math.min(len - numRead, this.curWord.length - this.curWordIdx);
      System.arraycopy(this.curWord, this.curWordIdx, cbuf, off + numRead, lenToRead);
      curWordIdx += lenToRead;
      outputOffset += lenToRead;
      numRead += lenToRead;
      if (curWordIdx == curWord.length) {
        this.readNextWord();
      }
    }
    return numRead;
  }


}
