package com.github.dbmdz.solrocr.iter;

import com.github.dbmdz.solrocr.reader.SourceReader;
import java.io.IOException;

/**
 * A break locator that wraps other {@link BreakLocator}s and aggregates their breaks to form larger
 * contexts.
 */
public class ContextBreakLocator implements BreakLocator {

  private final BreakLocator baseLocator;
  private final BreakLocator limitLocator;
  private final int contextSize;

  /** Wrap another BreakIterator and configure the output context size */
  public ContextBreakLocator(BreakLocator baseLocator, BreakLocator limitLocator, int contextSize) {
    this.baseLocator = baseLocator;
    this.limitLocator = limitLocator;
    this.contextSize = contextSize;
  }

  @Override
  public int following(int offset) throws IOException {
    int limit = getText().length();
    if (limitLocator != null) {
      limit = limitLocator.following(offset);
    }

    int idx = baseLocator.following(offset);
    if (idx >= limit) {
      return limit;
    }
    for (int i = 0; i < contextSize; i++) {
      int next = baseLocator.following(idx);
      if (next >= limit) {
        return limit;
      }
      idx = next;
    }
    return idx;
  }

  @Override
  public int preceding(int offset) throws IOException {
    int limit = 0;
    if (limitLocator != null) {
      limit = limitLocator.preceding(offset);
    }
    int idx = baseLocator.preceding(offset);
    if (idx <= limit) {
      return limit;
    }
    for (int i = 0; i < contextSize; i++) {
      int next = baseLocator.preceding(idx);
      if (next <= limit) {
        return limit;
      }
      idx = next;
    }
    return idx;
  }

  @Override
  public SourceReader getText() {
    return baseLocator.getText();
  }
}
