package org.mdz.search.solrocr.util;

import java.text.BreakIterator;
import java.text.CharacterIterator;

/**
 * A meta break iterator that wraps other {@link BreakIterator}s and aggregates their breaks to form larger contexts.
 *
 * NOTE: This class is **only** intended to be used with Solr/Lucene highlighters.
 * It only implements `getText`, `first`, `last`, `preceding` and `following`.
 */
public class ContextBreakIterator extends BreakIterator {
  private final BreakIterator baseIter;
  private final BreakIterator limitIter;
  private final int context;

  /** Wrap another BreakIterator and configure the output context size */
  public ContextBreakIterator(BreakIterator baseIter, BreakIterator limitIter, int contextSize) {
    this.baseIter = baseIter;
    this.limitIter = limitIter;
    this.context = contextSize;
  }

  @Override
  public int first() {
    return this.baseIter.first();
  }

  @Override
  public int last() {
    return this.baseIter.last();
  }

  @Override
  public int next(int n) {
    throw new UnsupportedOperationException();
  }

  @Override
  public int next() {
    int limit = Integer.MAX_VALUE;
    if (this.limitIter != null) {
      limit = this.limitIter.following(this.baseIter.current());
    }
    for (int i=0; i < context * 2 + 1; i++) {
      if (this.baseIter.next() >= limit) {
        return limit;
      };
    }
    return this.baseIter.current();
  }

  @Override
  public int previous() {
    int limit = -1;
    if (this.limitIter != null) {
      limit = this.limitIter.preceding(this.baseIter.current());
    }
    for (int i=0; i < context * 2 + 1; i++) {
      if (this.baseIter.previous() <= limit) {
        return limit;
      };
    }
    return this.baseIter.current();
  }

  @Override
  public int following(int offset) {
    int limit = Integer.MAX_VALUE;
    if (limitIter != null) {
      limit = this.limitIter.following(offset);
    }
    this.baseIter.following(offset);
    for (int i=0; i < context; i++) {
      if (this.baseIter.next() >= limit) {
        return limit;
      }
    }
    return this.baseIter.current();
  }

  @Override
  public int preceding(int offset) {
    int limit = -1;
    if (limitIter != null) {
      limit = this.limitIter.preceding(offset);
    }
    this.baseIter.preceding(offset);
    for (int i=0; i < context; i++) {
      if (this.baseIter.previous() <= limit) {
        return limit;
      }
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
    if (limitIter != null) {
      limitIter.setText(newText);
    }
  }
}
