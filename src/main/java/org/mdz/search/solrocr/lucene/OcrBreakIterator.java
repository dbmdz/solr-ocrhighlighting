package org.mdz.search.solrocr.lucene;

import java.text.BreakIterator;
import java.text.CharacterIterator;

public class OcrBreakIterator extends BreakIterator {
  /* TODO: Implement (labs/solr-ocr-plugin#5) */

  /**
   * Returns the first boundary. The iterator's current position is set
   * to the first text boundary.
   * @return The character index of the first text boundary.
   */
  @Override
  public int first() {
    return 0;
  }

  /**
   * Returns the last boundary. The iterator's current position is set
   * to the last text boundary.
   * @return The character index of the last text boundary.
   */
  @Override
  public int last() {
    return 0;
  }

  /**
   * Returns the nth boundary from the current boundary. If either
   * the first or last text boundary has been reached, it returns
   * <code>BreakIterator.DONE</code> and the current position is set to either
   * the first or last text boundary depending on which one is reached. Otherwise,
   * the iterator's current position is set to the new boundary.
   * For example, if the iterator's current position is the mth text boundary
   * and three more boundaries exist from the current boundary to the last text
   * boundary, the next(2) call will return m + 2. The new text position is set
   * to the (m + 2)th text boundary. A next(4) call would return
   * <code>BreakIterator.DONE</code> and the last text boundary would become the
   * new text position.
   * @param n which boundary to return.  A value of 0
   * does nothing.  Negative values move to previous boundaries
   * and positive values move to later boundaries.
   * @return The character index of the nth boundary from the current position
   * or <code>BreakIterator.DONE</code> if either first or last text boundary
   * has been reached.
   */
  @Override
  public int next(int n) {
    return 0;
  }

  /**
   * Returns the boundary following the current boundary. If the current boundary
   * is the last text boundary, it returns <code>BreakIterator.DONE</code> and
   * the iterator's current position is unchanged. Otherwise, the iterator's
   * current position is set to the boundary following the current boundary.
   * @return The character index of the next text boundary or
   * <code>BreakIterator.DONE</code> if the current boundary is the last text
   * boundary.
   * Equivalent to next(1).
   * @see #next(int)
   */
  @Override
  public int next() {
    return 0;
  }

  /**
   * Returns the boundary preceding the current boundary. If the current boundary
   * is the first text boundary, it returns <code>BreakIterator.DONE</code> and
   * the iterator's current position is unchanged. Otherwise, the iterator's
   * current position is set to the boundary preceding the current boundary.
   * @return The character index of the previous text boundary or
   * <code>BreakIterator.DONE</code> if the current boundary is the first text
   * boundary.
   */
  @Override
  public int previous() {
    return 0;
  }

  /**
   * Returns the first boundary following the specified character offset. If the
   * specified offset equals to the last text boundary, it returns
   * <code>BreakIterator.DONE</code> and the iterator's current position is unchanged.
   * Otherwise, the iterator's current position is set to the returned boundary.
   * The value returned is always greater than the offset or the value
   * <code>BreakIterator.DONE</code>.
   * @param offset the character offset to begin scanning.
   * @return The first boundary after the specified offset or
   * <code>BreakIterator.DONE</code> if the last text boundary is passed in
   * as the offset.
   * @exception  IllegalArgumentException if the specified offset is less than
   * the first text boundary or greater than the last text boundary.
   */
  @Override
  public int following(int offset) {
    return 0;
  }

  /**
   * Returns character index of the text boundary that was most
   * recently returned by next(), next(int), previous(), first(), last(),
   * following(int) or preceding(int). If any of these methods returns
   * <code>BreakIterator.DONE</code> because either first or last text boundary
   * has been reached, it returns the first or last text boundary depending on
   * which one is reached.
   * @return The text boundary returned from the above methods, first or last
   * text boundary.
   * @see #next()
   * @see #next(int)
   * @see #previous()
   * @see #first()
   * @see #last()
   * @see #following(int)
   * @see #preceding(int)
   */
  @Override
  public int current() {
    return 0;
  }

  /**
   * Get the text being scanned
   * @return the text being scanned
   */
  @Override
  public CharacterIterator getText() {
    return null;
  }

  /**
   * Set a new text string to be scanned.  The current scan
   * position is reset to first().
   * @param newText new text to scan.
   */
  @Override
  public void setText(CharacterIterator newText) {

  }
}
