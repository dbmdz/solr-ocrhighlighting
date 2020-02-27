package de.digitalcollections.solrocr.iter;

import de.digitalcollections.solrocr.model.SourcePointer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.text.CharacterIterator;
import java.text.StringCharacterIterator;
import java.util.stream.IntStream;

/** A combination interface of {@link java.lang.CharSequence} and {@link java.text.CharacterIterator}.
 *
 * This is needed because in various parts of the {@link org.apache.lucene.search.uhighlight.UnifiedHighlighter} that
 * this plugin wraps the content is accessed via both interfaces.
 */
public interface IterableCharSequence extends CharSequence, CharacterIterator {


  enum OffsetType {
    BYTES, CHARS
  }

  String getIdentifier();

  OffsetType getOffsetType();

  Charset getCharset();

  SourcePointer getPointer();

  static IterableCharSequence fromString(String string) {
    return new IterableStringCharSequence(string);
  }

  class IterableStringCharSequence implements IterableCharSequence {
    private String s;
    private StringCharacterIterator it;

    IterableStringCharSequence(String string) {
      this.s = string;
      this.it = new StringCharacterIterator(s);
    }

    private IterableStringCharSequence(String s, StringCharacterIterator it) {
      this.s = s;
      this.it = it;

    }

    @Override
    public int length() {
      return s.length();
    }

    @Override
    public char charAt(int index) {
      return s.charAt(index);
    }

    @Override
    public CharSequence subSequence(int beginIndex, int endIndex) {
      return s.subSequence(beginIndex, endIndex);
    }

    @Override
    public IntStream chars() {
      return s.chars();
    }

    @Override
    public IntStream codePoints() {
      return s.codePoints();
    }

    @Override
    public char first() {
      return it.first();
    }

    @Override
    public char last() {
      return it.last();
    }

    @Override
    public char setIndex(int p) {
      return it.setIndex(p);
    }

    @Override
    public char current() {
      return it.current();
    }

    @Override
    public char next() {
      return it.next();
    }

    @Override
    public char previous() {
      return it.previous();
    }

    @Override
    public int getBeginIndex() {
      return it.getBeginIndex();
    }

    @Override
    public int getEndIndex() {
      return it.getEndIndex();
    }

    @Override
    public int getIndex() {
      return it.getIndex();
    }

    @Override
    public Object clone() {
      return new IterableStringCharSequence(this.s, (StringCharacterIterator) this.it.clone());
    }

    @Override
    public String toString() {
      return this.s;
    }

    @Override
    public String getIdentifier() {
      return this.s.substring(0, 29) + "...";
    }

    @Override
    public OffsetType getOffsetType() {
      return OffsetType.CHARS;
    }

    @Override
    public Charset getCharset() {
      return StandardCharsets.UTF_16;
    }

    @Override
    public SourcePointer getPointer() {
      return null;
    }
  }
}
