package de.digitalcollections.solrocr.lucene;

import java.io.IOException;
import java.io.Reader;
import org.apache.lucene.analysis.charfilter.BaseCharFilter;

public class Utf8MappingCharFilter extends BaseCharFilter {
  private int cumulative;
  private int currentOffset;
  private boolean nextIsOffset = false;

  public Utf8MappingCharFilter(Reader in) {
    super(in);
    this.cumulative = 0;
    this.currentOffset = cumulative;
  }

  @Override
  public int read(char[] cbuf, int off, int len) throws IOException {
    int numRead = 0;
    while (len - numRead > 0) {
      int lenToRead = len - numRead;
      int read = this.input.read(cbuf, off, lenToRead);
      if (read < 0) {
        if (numRead == 0) {
          return -1;
        }
        break;
      }
      this.correctOffsets(cbuf, off, read);
      off += read;
      numRead += read;
    }
    return numRead;
  }

  private void correctOffsets(char[] cbuf, int off, int len) {
    for (int i=off; i < off + len; i++) {
      if (nextIsOffset) {
        this.addOffCorrectMap(currentOffset, cumulative);
        nextIsOffset = false;
      }
      currentOffset += 1;
      int cp = Character.codePointAt(cbuf, i);
      int increment = charUtf8Size(cp) - 1;
      if (increment > 0) {
        cumulative += increment;
        nextIsOffset = true;
      }
    }
  }

  private static int charUtf8Size(int cp) {
    if (cp < 0x80) {
      return 1;
    } else if (cp < 0x800) {
      return 2;
    } else if (cp < 0x10000 || cp >= 0x110000) {
      return 3;
    } else {
      return 4;
    }
  }
}
