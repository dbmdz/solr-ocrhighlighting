package de.digitalcollections.solrocr.iter;

import java.text.BreakIterator;
import java.text.CharacterIterator;

/** A {@link java.text.BreakIterator} that splits an XML-like document on a specific opening or closing tag. */
public class TagBreakIterator extends BreakIterator {
  private final String breakTag;
  private CharacterIterator text;
  private int current;

  public TagBreakIterator(String tagName) {
    this(tagName, false);
  }

  public TagBreakIterator(String tagName, boolean closing) {
    if (closing) {
      this.breakTag = "</" + tagName + ">";
    } else {
      this.breakTag = "<" + tagName;
    }
  }

  @Override
  public int first() {
    this.text.first();
    this.current = this.text.getIndex();
    return this.current();
  }

  @Override
  public int last() {
    this.text.last();
    this.current = this.text.getIndex();
    return this.current();
  }

  @Override
  public int next(int n) {
    for (int i=n; i > 0; i++) {
      this.next();
    }
    return this.current;
  }

  @Override
  public int next() {
    String tag = "";
    StringBuilder sb = null;
    while (!tag.startsWith(breakTag)) {
      char c = this.text.current();
      if (c == '<') {
        sb = new StringBuilder();
      }
      if (sb != null) {
        sb.append(c);
        if (c == '>') {
          tag = sb.toString();
          sb = null;
        }
      }
      if (this.text.next() == CharacterIterator.DONE) {
        this.current = this.text.getIndex();
        return this.current;
      }
    }
    // FIXME: This will break with ByteCharIterators if the tag has a multi-byte codepoint.
    this.current = this.text.getIndex() - tag.length();
    return this.current;
  }

  @Override
  public int previous() {
    String tag = "";
    StringBuilder sb = null;
    while (!tag.startsWith(breakTag)) {
      char c = this.text.current();
      if (c == '>') {
        sb = new StringBuilder();
      }
      if (sb != null) {
        sb.insert(0, c);
        if (c == '<') {
          tag = sb.toString();
          sb = null;
        }
      }
      if (this.text.previous() == CharacterIterator.DONE) {
        this.current = this.text.getIndex();
        return this.current;
      }
    }
    // FIXME: This will break with ByteCharIterators if the tag has a multi-byte codepoint.
    this.current = this.text.getIndex() + 1;
    return this.current;
  }

  @Override
  public int following(int offset) {
    this.text.setIndex(offset);
    return this.next();
  }

  @Override
  public int preceding(int offset) {
    this.text.setIndex(offset);
    return this.previous();
  }

  @Override
  public int current() {
    return this.current;
  }

  @Override
  public CharacterIterator getText() {
    return text;
  }

  @Override
  public void setText(CharacterIterator newText) {
    this.current = 0;
    this.text = newText;
  }
}
