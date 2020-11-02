package de.digitalcollections.solrocr.util;

public class CharBufUtils {
  public static int indexOf(char[] needle, int offset, char[] haystack, int haystackLen) {
    int needleIdx = 0;
    while (needleIdx < needle.length && offset < haystackLen) {
      if (haystack[offset] == needle[needleIdx]) {
        needleIdx++;
      } else {
        needleIdx = 0;
      }
      offset++;
    }

    if (needleIdx == needle.length) {
      return offset - needle.length;
    }
    return -1;
  }
}
