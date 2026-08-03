package com.github.dbmdz.solrocr.util;

/** Some utility functions to work with character buffers. */
public class CharBufUtils {

  /**
   * Find the offset of the needle in the haystack.
   *
   * <p>⚠️ This is <strong>not</strong> a drop-in replacement for {@link String#indexOf}. It is a
   * single-pass O(n) scan tuned for the needles actually used in this codebase (a leading space
   * followed by an XML attribute name, or a run of identical Unicode markers). On a failed partial
   * match the current character is re-checked against {@code needle[0]} to avoid skipping a valid
   * match start when the first needle character repeats consecutively in the haystack. Full
   * backtracking (as in KMP or a naive O(n*m) search) is intentionally omitted for performance on
   * the hot indexing path.
   *
   * <p><strong>Before adding a new call site:</strong> make sure your needle doesn't have complex
   * internal overlaps (e.g. {@code "ABAB"}, {@code "AAA"}) — those may produce wrong results. If in
   * doubt, use {@code new String(haystack, fromIndex, toIndex - fromIndex).indexOf(new
   * String(needle))} instead.
   *
   * @param haystack target buffer to find characters in
   * @param fromIndex offset to start looking for in the haystack buffer
   * @param toIndex length of target buffer, everything beyond this offset is ignored
   * @param needle sequence of chars to look for in the haystack buffer
   * @return the starting offset of {@code needle} in the {@code haystack} or -1 if not found
   */
  public static int indexOf(char[] haystack, int fromIndex, int toIndex, char[] needle) {
    int needleIdx = 0;
    while (needleIdx < needle.length && fromIndex < toIndex) {
      if (haystack[fromIndex] == needle[needleIdx]) {
        needleIdx++;
      } else if (haystack[fromIndex] == needle[0]) {
        needleIdx = 1;
      } else {
        needleIdx = 0;
      }
      fromIndex++;
    }

    if (needleIdx == needle.length) {
      return fromIndex - needle.length;
    }
    return -1;
  }
}
