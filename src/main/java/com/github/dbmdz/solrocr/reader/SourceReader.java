package com.github.dbmdz.solrocr.reader;

import com.github.dbmdz.solrocr.model.SourcePointer;
import java.io.IOException;

public interface SourceReader {
  void close() throws IOException;

  SourcePointer getPointer();

  String getIdentifier();

  int length() throws IOException;

  String readAsciiString(int start, int len) throws IOException;

  String readUtf8String(int start, int byteLen) throws IOException;

  Section getAsciiSection(int offset) throws IOException;

  enum AdjustDirection {
    LEFT,
    RIGHT
  }

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
