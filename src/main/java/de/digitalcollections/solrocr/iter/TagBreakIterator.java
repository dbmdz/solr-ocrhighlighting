package de.digitalcollections.solrocr.iter;

import java.text.BreakIterator;
import java.text.CharacterIterator;

/** A {@link java.text.BreakIterator} that splits an XML-like document on a specific opening or closing tag. */
public class TagBreakIterator extends BreakIterator {
  private final String breakTag;
  //private final char[] breakTag;
  private CharacterIterator text;
  private int current;

  public TagBreakIterator(String tagName) {
    this(tagName, false);
  }

  public TagBreakIterator(String tagName, boolean closing) {
    if (closing) {
      this.breakTag = ("</" + tagName + ">");//.toCharArray();
    } else {
      this.breakTag = ("<" + tagName);//.toCharArray();
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
    for (int i=n; i > 0; i--) {
      this.next();
    }
    return this.current;
  }

  private int nextBuffered() {
    int blockSize = 65536;
    int overlap = this.breakTag.length();
    IterableCharSequence seq = (IterableCharSequence) this.text;
    int textIdx = this.text.getIndex();
    int start = textIdx;
    int end = Math.min(textIdx + blockSize, this.text.getEndIndex());
    while (start < this.text.getEndIndex()) {
      String block = seq.subSequence(start, end, true).toString();
      int idx = block.indexOf(breakTag);
      if (idx >= 0) {
        return start + idx;
      }
      start = end;
      if (end < this.text.getEndIndex()) {
        end -= overlap;
      }
      end = Math.min(end + blockSize, this.text.getEndIndex());
    }
    return this.text.getEndIndex();
  }

  private int previousBuffered() {
    int blockSize = 65536;
    int overlap = this.breakTag.length();
    IterableCharSequence seq = (IterableCharSequence) this.text;
    int textIdx = this.text.getIndex();
    int end = textIdx;
    int start = Math.max(0, textIdx - blockSize);
    while (start >= this.text.getBeginIndex()) {
      String block = seq.subSequence(start, end, true).toString();
      int idx = block.lastIndexOf(breakTag);
      if (idx >= 0) {
        return start + idx;
      }
      end = start + overlap;
      start = Math.max(0, start - blockSize);
    }
    return this.text.getBeginIndex();
  }

  @Override
  public int next() {
    if (this.text instanceof IterableCharSequence) {
      this.current = this.nextBuffered();
      this.text.setIndex(
          Math.min(this.current + breakTag.length(), this.text.getEndIndex()));
    } else {
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
    }
    return this.current;
  }

  @Override
  public int previous() {
    if (this.text instanceof IterableCharSequence) {
      this.current = this.previousBuffered();
      this.text.setIndex(this.current);
    } else {
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
    }
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
