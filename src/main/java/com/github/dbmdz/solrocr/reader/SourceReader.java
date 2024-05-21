package com.github.dbmdz.solrocr.reader;

import com.github.dbmdz.solrocr.model.SourcePointer;

public interface SourceReader {
  void close();

  SourcePointer getPointer();

  String getIdentifier();

  int length();

  String readAsciiString(int start, int len);

  String readUtf8String(int start, int byteLen);

  Section getAsciiSection(int offset);

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
