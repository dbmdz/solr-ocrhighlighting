package de.digitalcollections.solrocr.iter;

public interface BreakLocator {
  int DONE = -1;

  int following(int offset);
  int preceding(int offset);
  IterableCharSequence getText();

}
