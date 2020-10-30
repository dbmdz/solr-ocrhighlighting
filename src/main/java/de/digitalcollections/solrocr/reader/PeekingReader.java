package de.digitalcollections.solrocr.reader;

import java.io.IOException;
import java.io.Reader;
import org.apache.lucene.analysis.charfilter.BaseCharFilter;

/** Reader class that supports "peeking forward" into the beginning of the stream and "peeking backward" into a fixed
 * window of previously read data. */
public class PeekingReader extends BaseCharFilter {
  /** Buffer to hold the beginning of the input reader. */
  private final char[] peekStart;

  /** How much of the input buffer has been re-used for writing out data via `read` */
  private int peekStartOffset = 0;

  /** Buffer to hold the back context accumulated from previous reads. */
  private char[] backContext;

  /** Offset in the input reader. */
  private long inputOffset = 0;

  /** Number of context chars in the input buffer. */
  private int backContextSize = 0;

  /**
   * Construct a new peeking reader with the given buffer sizes
   * @param in input Reader instance
   * @param beginPeekSize number of characters to buffer from the beginning of the string
   * @param maxBackContextSize number of characters to buffer from previous reads
   */
  public PeekingReader(Reader in, int beginPeekSize, int maxBackContextSize) {
    super(in);
    try {
      // Set up the beginning peek buffer
      char[] buf = new char[beginPeekSize];
      int numRead = this.input.read(buf, 0, beginPeekSize);
      if (numRead < beginPeekSize) {
        // If the input reader has less characters than the requested start peek buffer, use a smaller buffer
        this.peekStart = new char[numRead];
        System.arraycopy(buf, 0, this.peekStart, 0, numRead);
      } else {
        this.peekStart = buf;
      }

      // Set up the back context
      this.backContext = new char[maxBackContextSize];
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public int read(char[] cbuf, int off, int len) throws IOException {
    int numRead = 0;
    int writeOff = off;
    // Empty start peek-buffer first
    if (peekStartOffset < peekStart.length) {
      int restLen = Math.min(peekStart.length - peekStartOffset, cbuf.length - off);
      System.arraycopy(peekStart, peekStartOffset, cbuf, writeOff, restLen);
      numRead += restLen;
      writeOff += numRead;
      peekStartOffset += numRead;
    }
    if (len > numRead) {
      numRead += this.input.read(cbuf, writeOff, len - numRead);
    }

    // Nothing read, no need to update back context
    if (numRead <= 0) {
      return numRead;
    }

    // Update back context buffer
    int ctxLen = this.backContext.length;
    int ctxFillLen = this.backContextSize;
    if (numRead >= ctxLen || (ctxFillLen + numRead) <= ctxLen) {
      // Append to or completely replace the back context buffer
      int srcOffset = Math.max(off, off + numRead - ctxLen);
      int dstOffset;
      if (numRead >= ctxLen) {
        dstOffset = 0;
      } else {
        dstOffset = ctxFillLen;
      }
      int copyLen = Math.min(numRead, ctxLen);
      System.arraycopy(cbuf, srcOffset, this.backContext, dstOffset, copyLen);
      this.backContextSize = Math.min(ctxFillLen + copyLen, ctxLen);
    } else {
      // Shift back context
      char[] newContext = new char[ctxLen];
      // How much to copy over from the old context
      int srcLen = ctxLen - numRead;
      // Copy over old context starting from where?
      int srcOff = ctxFillLen - srcLen;
      System.arraycopy(this.backContext, srcOff, newContext, 0, srcLen);
      System.arraycopy(cbuf, off, newContext, srcLen, numRead);
      this.backContext = newContext;
      this.backContextSize = ctxLen;
    }
    inputOffset += numRead;
    return numRead;
  }

  /** Peek into the beginning of the input reader without affecting the current reader position. */
  public String peekBeginning() {
    return new String(peekStart);
  }

  /** Peek into the back-context, i.e. the previously read data without seeking back in the stream. */
  public String peekBackContext() {
    return new String(backContext, 0, this.backContextSize);
  }

  /** Get the start offset of the back context in the input reader. */
  public long getBackContextStartOffset() {
    return Math.max(0, this.inputOffset - this.backContext.length);
  }

  /** Get the maximum supported size of the back context. */
  public int getMaxBackContextSize() {
    return this.backContext.length;
  }
}
