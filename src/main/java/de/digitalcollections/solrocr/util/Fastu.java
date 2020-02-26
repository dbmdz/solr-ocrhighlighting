/*
Copyright (c) 2011 Jonathan Leibiusky

Permission is hereby granted, free of charge, to any person
obtaining a copy of this software and associated documentation
files (the "Software"), to deal in the Software without
restriction, including without limitation the rights to use,
copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the
Software is furnished to do so, subject to the following
conditions:

The above copyright notice and this permission notice shall be
included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
OTHER DEALINGS IN THE SOFTWARE.
*/
package de.digitalcollections.solrocr.util;

public class Fastu {
  public static String decode(byte[] data) {
    char[] chars = new char[data.length];
    int len = 0;
    int offset = 0;
    while (offset < data.length) {
      if ((data[offset] & 0x80) == 0) {
        // 0xxxxxxx - it is an ASCII char, so copy it exactly as it is
        chars[len] = (char) data[offset];
        len++;
        offset++;
      } else {
        int uc = 0;
        if ((data[offset] & 0xE0) == 0xC0) {
          uc = (int) (data[offset] & 0x1F);
          offset++;
          uc <<= 6;
          uc |= (int) (data[offset] & 0x3F);
          offset++;
        } else if ((data[offset] & 0xF0) == 0xE0) {
          uc = (int) (data[offset] & 0x0F);
          offset++;
          uc <<= 6;
          uc |= (int) (data[offset] & 0x3F);
          offset++;
          uc <<= 6;
          uc |= (int) (data[offset] & 0x3F);
          offset++;

        } else if ((data[offset] & 0xF8) == 0xF0) {
          uc = (int) (data[offset] & 0x07);
          offset++;
          uc <<= 6;
          uc |= (int) (data[offset] & 0x3F);
          offset++;
          uc <<= 6;
          uc |= (int) (data[offset] & 0x3F);
          offset++;
          uc <<= 6;
          uc |= (int) (data[offset] & 0x3F);
          offset++;

        } else if ((data[offset] & 0xFC) == 0xF8) {
          uc = (int) (data[offset] & 0x03);
          offset++;
          uc <<= 6;
          uc |= (int) (data[offset] & 0x3F);
          offset++;
          uc <<= 6;
          uc |= (int) (data[offset] & 0x3F);
          offset++;
          uc <<= 6;
          uc |= (int) (data[offset] & 0x3F);
          offset++;
          uc <<= 6;
          uc |= (int) (data[offset] & 0x3F);
          offset++;

        } else if ((data[offset] & 0xFE) == 0xFC) {
          uc = (int) (data[offset] & 0x01);
          offset++;
          uc <<= 6;
          uc |= (int) (data[offset] & 0x3F);
          offset++;
          uc <<= 6;
          uc |= (int) (data[offset] & 0x3F);
          offset++;
          uc <<= 6;
          uc |= (int) (data[offset] & 0x3F);
          offset++;
          uc <<= 6;
          uc |= (int) (data[offset] & 0x3F);
          offset++;
          uc <<= 6;
          uc |= (int) (data[offset] & 0x3F);
          offset++;
        }

        len = toChars(uc, chars, len);
      }
    }
    return new String(chars, 0, len);
  }

  public static int toChars(int codePoint, char[] dst, int index) {
    if (codePoint < 0 || codePoint > Character.MAX_CODE_POINT) {
      throw new IllegalArgumentException();
    }
    if (codePoint < Character.MIN_SUPPLEMENTARY_CODE_POINT) {
      dst[index] = (char) codePoint;
      return ++index;
    }
    int offset = codePoint - Character.MIN_SUPPLEMENTARY_CODE_POINT;
    dst[index + 1] = (char) ((offset & 0x3ff) + Character.MIN_LOW_SURROGATE);
    dst[index] = (char) ((offset >>> 10) + Character.MIN_HIGH_SURROGATE);
    return index + 2;
  }
}
