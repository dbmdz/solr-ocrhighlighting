package de.digitalcollections.solrocr.util;

/** Some utility functions to work with character buffers */
public class CharBufUtils {
  /** Find the offset of the needle in the haystack.
   *
   * FIXME: Is there really no stdlib method for this?
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

  public static boolean isWhitespace(char[] buf, int from, int len) {
    for (int i=from; i < from + len; i++) {
      if (!Character.isWhitespace(buf[i])) {
        return false;
      }
    }
    return true;
  }
}
