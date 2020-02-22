package de.digitalcollections.solrocr.iter;

import de.digitalcollections.solrocr.model.SourcePointer;
import java.nio.charset.Charset;
import java.util.stream.IntStream;
import org.apache.lucene.index.QueryTimeout;

public class ExitingIterCharSeq implements IterableCharSequence {
  public static class ExitingIterCharSeqException extends RuntimeException {
    ExitingIterCharSeqException(String msg) {
      super(msg);
    }
  }

  private final static int CHARS_BETWEEN_CHECKS = 8192;

  private final IterableCharSequence iter;
  private final QueryTimeout timeout;
  private int untilNextCheck = CHARS_BETWEEN_CHECKS;

  public ExitingIterCharSeq(IterableCharSequence iter, QueryTimeout timeout) {
    this.iter = iter;
    this.timeout = timeout;
  }

  private void checkAndThrow() {
    if (timeout.shouldExit()) {
      throw new ExitingIterCharSeqException(String.format(
          "The request took to long to highlight the OCR files (pointer: %s, timeout was: %s)", getPointer(), timeout));
    } else if (Thread.interrupted()) {
      throw new ExitingIterCharSeqException(String.format(
          "Interrupted while reading the file for OCR (pointer: %s).", getPointer()));
    }
  }

  @Override
  public String getIdentifier() {
    return iter.getIdentifier();
  }

  @Override
  public OffsetType getOffsetType() {
    return iter.getOffsetType();
  }

  @Override
  public Charset getCharset() {
    return iter.getCharset();
  }

  @Override
  public SourcePointer getPointer() {
    return iter.getPointer();
  }

  public static IterableCharSequence fromString(String string) {
    return IterableCharSequence.fromString(string);
  }

  @Override
  public int length() {
    return iter.length();
  }

  @Override
  public char charAt(int index) {
    untilNextCheck--;
    if (untilNextCheck == 0) {
      checkAndThrow();
      untilNextCheck = CHARS_BETWEEN_CHECKS;
    }
    return iter.charAt(index);
  }

  @Override
  public CharSequence subSequence(int start, int end) {
    return iter.subSequence(start, end);
  }

  @Override
  public String toString() {
    return iter.toString();
  }

  @Override
  public IntStream chars() {
    return iter.chars();
  }

  @Override
  public IntStream codePoints() {
    return iter.codePoints();
  }

  @Override
  public char first() {
    return iter.first();
  }

  @Override
  public char last() {
    return iter.last();
  }

  @Override
  public char current() {
    return iter.current();
  }

  @Override
  public char next() {
    return iter.next();
  }

  @Override
  public char previous() {
    return iter.previous();
  }

  @Override
  public char setIndex(int position) {
    return iter.setIndex(position);
  }

  @Override
  public int getBeginIndex() {
    return iter.getBeginIndex();
  }

  @Override
  public int getEndIndex() {
    return iter.getEndIndex();
  }

  @Override
  public int getIndex() {
    return iter.getIndex();
  }

  @Override
  public Object clone() {
    return new ExitingIterCharSeq(iter, timeout);
  }
}
