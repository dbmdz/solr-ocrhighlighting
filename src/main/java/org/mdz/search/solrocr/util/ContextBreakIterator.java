package org.mdz.search.solrocr.util;

import java.text.BreakIterator;
import java.text.CharacterIterator;

/**
 * NOTE: This class is **only** intended to be used with Solr/Lucene highlighters.
 * It only implements `getText`, `preceding` and `following`.
 */
public class ContextBreakIterator extends BreakIterator {
  private final BreakIterator baseIter;
  private final int context;

  public ContextBreakIterator(BreakIterator baseIter, int context) {
    this.baseIter = baseIter;
    this.context = context;
  }

  @Override
  public int first() {
    throw new UnsupportedOperationException();
  }

  @Override
  public int last() {
    throw new UnsupportedOperationException();
  }

  @Override
  public int next(int n) {
    throw new UnsupportedOperationException();
  }

  @Override
  public int next() {
    throw new UnsupportedOperationException();
  }

  @Override
  public int previous() {
    throw new UnsupportedOperationException();
  }

  @Override
  public int following(int offset) {
    this.baseIter.following(offset);
    for (int i=0; i < context; i++) {
      this.baseIter.next();
    }
    return this.baseIter.current();
  }

  @Override
  public int preceding(int offset) {
    this.baseIter.preceding(offset);
    for (int i=0; i < context; i++) {
      this.baseIter.previous();
    }
    return this.baseIter.current();
  }

  @Override
  public int current() {
    return baseIter.current();
  }

  @Override
  public CharacterIterator getText() {
    return baseIter.getText();
  }

  @Override
  public void setText(CharacterIterator newText) {
    baseIter.setText(newText);
  }
}
