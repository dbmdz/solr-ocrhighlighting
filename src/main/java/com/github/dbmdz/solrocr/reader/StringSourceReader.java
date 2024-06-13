package com.github.dbmdz.solrocr.reader;

import com.github.dbmdz.solrocr.model.SourcePointer;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/** SourceReader that reads from a String. */
public class StringSourceReader implements SourceReader {

  private final String str;

  public StringSourceReader(String str) {
    this.str = str;
  }

  @Override
  public String readAsciiString(int start, int len) {
    // This is semantically incorrect, but it doesn't cause any harm
    return this.str.substring(start, start + len);
  }

  @Override
  public String readUtf8String(int start, int byteLen) {
    // This is semantically incorrect, but it doesn't cause any harm
    return this.str.substring(start, start + byteLen);
  }

  @Override
  public Section getAsciiSection(int offset) {
    return new Section(0, str.length(), str);
  }

  @Override
  public int readBytes(ByteBuffer dst, int start) {
    byte[] bytes = str.getBytes(StandardCharsets.UTF_8);
    int limit = Math.min(dst.remaining(), bytes.length);
    dst.put(bytes, 0, limit);
    return limit;
  }

  @Override
  public int length() {
    return this.str.length();
  }

  @Override
  public void close() {
    // NOP
  }

  @Override
  public SourcePointer getPointer() {
    return null;
  }

  @Override
  public String getIdentifier() {
    return String.format("StringSourceReader-%d", str.hashCode());
  }
}
