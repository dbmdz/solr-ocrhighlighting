package com.github.dbmdz.solrocr.reader;

import com.github.dbmdz.solrocr.model.SourcePointer;
import java.io.IOException;

/** API for reading data from a source. */
public interface SourceReader {
  /** Close the resources associated with this reader. */
  void close() throws IOException;

  /** Get the pointer this reader is reading from. */
  SourcePointer getPointer();

  /** Get the internal identifier of the source. */
  String getIdentifier();

  /** Get the number of bytes in the source. */
  int length() throws IOException;

  /** Read a section from the source as an ASCII/Latin1 string.

   * This method should be implemented as fast as possible, ideally without going
   * through a decoder.
   */
  String readAsciiString(int start, int len) throws IOException;

  /** Read a section from the source as an UTF8 string. */
  String readUtf8String(int start, int byteLen) throws IOException;

  /** Read a section aligned to this reader's section size.
   *
   * <p>
   * This method should be implemented as efficiently as possible, since it's called
   * in the hottest loop of the highlighting logic (passage formation)*/
  Section getAsciiSection(int offset) throws IOException;

  class Section {
    public final int start;
    public final int end;
    public final String text;

    public Section(int start, int end, String text) {
      /* Start byte offset of the section, inclusive */
      this.start = start;
      /* End byte offset of the section, exclusive */
      this.end = end;
      /* ASCII text of the section */
      this.text = text;
    }

    @Override
    public String toString() {
      return "Section{" + "start=" + start + ", end=" + end + '}';
    }
  }
}
