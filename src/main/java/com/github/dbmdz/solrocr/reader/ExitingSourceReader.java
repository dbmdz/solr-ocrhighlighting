package com.github.dbmdz.solrocr.reader;

import com.github.dbmdz.solrocr.model.SourcePointer;
import java.util.Locale;
import org.apache.lucene.index.QueryTimeout;

public class ExitingSourceReader implements SourceReader {
  public static class ExitingSourceReaderException extends RuntimeException {
    ExitingSourceReaderException(String msg) {
      super(msg);
    }
  }

  private final SourceReader input;
  private final QueryTimeout timeout;

  public ExitingSourceReader(SourceReader input, QueryTimeout timeout) {
    this.input = input;
    this.timeout = timeout;
  }

  private void checkAndThrow() {
    if (timeout.shouldExit()) {
      throw new ExitingSourceReaderException(
          String.format(
              Locale.US,
              "The request took to long to highlight the OCR files (pointer: %s, timeout was: %s)",
              input.getPointer(),
              timeout));
    } else if (Thread.interrupted()) {
      throw new ExitingSourceReaderException(
          String.format(
              Locale.US,
              "Interrupted while reading the file for OCR (pointer: %s).",
              input.getPointer()));
    }
  }

  @Override
  public void close() {
    input.close();
  }

  @Override
  public SourcePointer getPointer() {
    return input.getPointer();
  }

  @Override
  public String getIdentifier() {
    return input.getIdentifier();
  }

  @Override
  public int length() {
    return input.length();
  }

  @Override
  public String readAsciiString(int start, int len) {
    checkAndThrow();
    return input.readAsciiString(start, len);
  }

  @Override
  public String readUtf8String(int start, int byteLen) {
    checkAndThrow();
    return input.readUtf8String(start, byteLen);
  }

  @Override
  public Section getAsciiSection(int offset) {
    checkAndThrow();
    return input.getAsciiSection(offset);
  }
}
