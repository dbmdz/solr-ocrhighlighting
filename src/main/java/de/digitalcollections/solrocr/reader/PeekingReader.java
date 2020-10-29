package de.digitalcollections.solrocr.reader;

import java.io.IOException;
import java.io.Reader;
import org.apache.lucene.analysis.charfilter.BaseCharFilter;

public class PeekingReader extends BaseCharFilter {
  private final String peek;
  private int peekOffset = 0;

  public PeekingReader(Reader in, int peekSize) {
    super(in);
    char[] peekBuf = new char[peekSize];
    try {
      int numRead = this.input.read(peekBuf, 0, peekSize);
      this.peek = new String(peekBuf, 0, numRead);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public int read(char[] cbuf, int off, int len) throws IOException {
    int numRead = 0;
    if (peekOffset < peek.length()) {
      int restLen = Math.min(peek.length() - peekOffset, cbuf.length - off);
      System.arraycopy(peek.toCharArray(), peekOffset, cbuf, off, restLen);
      numRead += restLen;
      off += numRead;
      peekOffset += numRead;
    }
    if (len > numRead) {
      numRead += this.input.read(cbuf, off, len - numRead);
    }
    return numRead;
  }

  public String peekBeginning() {
    return peek;
  }
}
