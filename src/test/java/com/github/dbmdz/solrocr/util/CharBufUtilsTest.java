package com.github.dbmdz.solrocr.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

public class CharBufUtilsTest {

  @Test
  public void testExactMatch() {
    char[] haystack = "hello world".toCharArray();
    char[] needle = "world".toCharArray();
    assertEquals(6, CharBufUtils.indexOf(haystack, 0, haystack.length, needle));
  }

  @Test
  public void testNoMatch() {
    char[] haystack = "hello world".toCharArray();
    char[] needle = "xyz".toCharArray();
    assertEquals(-1, CharBufUtils.indexOf(haystack, 0, haystack.length, needle));
  }

  @Test
  public void testRepeatedFirstChar() {
    char[] haystack = "AAB".toCharArray();
    char[] needle = "AB".toCharArray();
    assertEquals(1, CharBufUtils.indexOf(haystack, 0, haystack.length, needle));
  }

  @Test
  public void testRepeatedWhitespace() {
    char[] haystack = ("                          CONTENT=").toCharArray();
    char[] needle = " CONTENT=".toCharArray();
    int result = CharBufUtils.indexOf(haystack, 0, haystack.length, needle);
    assertEquals(25, result);
    assertEquals('=', haystack[result + needle.length - 1]);
  }

  @Test
  public void testFromIndex() {
    char[] haystack = "hello world".toCharArray();
    char[] needle = "world".toCharArray();
    assertEquals(6, CharBufUtils.indexOf(haystack, 3, haystack.length, needle));
    assertEquals(-1, CharBufUtils.indexOf(haystack, 7, haystack.length, needle));
  }
}
