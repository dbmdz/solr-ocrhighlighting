package com.github.dbmdz.solrocr.reader;

public class StringSourceReader extends BaseSourceReader {

  private final String str;

  public StringSourceReader(String str) {
    super(null, str.length(), 0);
    this.str = str;
  }

  @Override
  protected int readBytes(byte[] dst, int dstOffset, int start, int len) {
    throw new UnsupportedOperationException();
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
  public int length() {
    return this.str.length();
  }

  @Override
  public void close() {
    // NOP
  }

  @Override
  public String getIdentifier() {
    return String.format("StringSourceReader-%d", str.hashCode());
  }
}
