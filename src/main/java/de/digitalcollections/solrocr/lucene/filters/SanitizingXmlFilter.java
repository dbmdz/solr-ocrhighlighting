package de.digitalcollections.solrocr.lucene.filters;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.stream.Collectors;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.lucene.analysis.charfilter.BaseCharFilter;

/** Character filter that "sanitizes" malformed input XML by inserting closing tags for unmatched opening tags and
 *  dropping unmatched closing tags, while maintaining the input offsets.
 *
 *  We have to deal with malformed XML, since we allow users to index relatively arbitrary regions from input files
 *  and also concatenate them with each other, resulting in some unmatched opening/ending tags. This didn't use to be
 *  a problem since we used a RegEx-based parsing approach, but with the new StAX-based approach we have to make sure
 *  that the XML we feed to the parser is well-formed.
 */
public class SanitizingXmlFilter extends BaseCharFilter {
  private final Deque<char[]> elementStack = new ArrayDeque<>();
  private char[] carryOver = null;
  private int carryOverIdx = -1;
  private char[] tail = null;
  private int tailIdx = -1;
  private boolean hasDocType = false;

  public SanitizingXmlFilter(Reader in) {
    super(in);
  }

  boolean tagEquals(char[] markupBuf, int startTag, int tagLen, char[] checkTag) {
    if (tagLen != checkTag.length) {
      return false;
    }
    for (int i=0; i < tagLen; i++) {
      if (markupBuf[startTag + i] != checkTag[i]) {
        return false;
      }
    }
    return true;
  }

  @Override
  public int read(char[] cbuf, int off, int len) throws IOException {
    int numRead = 0;
    int writeOffset = off;
    if (this.carryOver != null) {
      // FIXME: This hasn't really been properly tested with small `len` values, likely to break
      int readLen = Math.min(this.carryOver.length - this.carryOverIdx, len);
      System.arraycopy(this.carryOver, 0, cbuf, off, readLen);
      writeOffset += readLen;
      len -= readLen;
      numRead += readLen;
      carryOverIdx += readLen;
      if (carryOverIdx == this.carryOver.length) {
        this.carryOver = null;
        this.carryOverIdx = -1;
      }
    }
    int numInputRead = this.input.read(cbuf, writeOffset, len);
    numRead += numInputRead;
    int idx = off;
    boolean truncated = false;
    while (idx < (off + numRead)) {
      int startElem = ArrayUtils.indexOf(cbuf, '<', idx);
      if (startElem < 0 || startElem > (off + numRead)) {
        // No more elements in this buffer, exit loop
        break;
      }
      int endElem = ArrayUtils.indexOf(cbuf, '>', startElem + 1);
      if (endElem < 0 || endElem > (off + numRead)) {
        // End of element is not part of this buffer, reduce read size so the element is fully part of the next read
        truncated = true;
        this.carryOver = new char[numRead - (startElem - off)];
        System.arraycopy(cbuf, startElem, this.carryOver, 0, this.carryOver.length);
        this.carryOverIdx = 0;
        numRead -= this.carryOver.length;
        break;
      }
      idx = endElem + 1;
      if (cbuf[startElem + 1] == '?' || (cbuf[startElem + 1] == '!' && cbuf[startElem + 2] == '-')) {
        // XML Declaration or comment, nothing to do
        continue;
      }


      // Strip out duplicate doctype declarations, these break the multidoc parsing mode in Woodstox
      if (cbuf[startElem  + 1] == '!' && (cbuf[startElem + 2] == 'D' || cbuf[startElem + 2] == 'd')) {
        if (hasDocType) {
          for (int i = startElem; i <= endElem; i++) {
            cbuf[i] = ' ';
          }
        } else {
          hasDocType = true;
        }
        continue;
      }

      int startTag = cbuf[startElem + 1] == '/' ? startElem + 2 : startElem + 1;
      int endTag = ArrayUtils.indexOf(cbuf, ' ', startTag);
      if (endTag > endElem || endTag < 0) {
        endTag = cbuf[endElem - 1] == '/' ? endElem - 1 : endElem;
      }
      int tagLen = endTag - startTag;
      if (cbuf[startElem + 1] == '/') {
        char[] checkTag = elementStack.peek();
        if (checkTag == null || !tagEquals(cbuf, startTag, tagLen, checkTag)) {
          // Closing tag doesn't match last opened tag, "zero out" the tag by replacing it with whitespace
          // Yeah, this is pure cheating, a proper CharFilter implementation would strip those chars from the output,
          // but this would require more copying around of data, and we normalize whitespace anyway down the line, so
          // this isn't really an issue for now (I think....)
          for (int i = startElem; i <= endElem; i++) {
            cbuf[i] = ' ';
          }
        } else {
          elementStack.pop();
        }
      } else if (cbuf[endElem - 1] != '/') {
        // New open tag, add to stack
        char[] newTag = new char[tagLen];
        System.arraycopy(cbuf, startTag, newTag, 0, newTag.length);
        elementStack.push(newTag);
      }
    }
    if (!truncated && numRead < len && !elementStack.isEmpty()) {
      if (this.tail == null) {
        this.tail = elementStack.stream()
            .map(tag -> "</" + new String(tag) + ">")
            .collect(Collectors.joining("")).toCharArray();
        this.tailIdx = 0;
      } else if (this.tailIdx == this.tail.length) {
        return -1;
      }

      int toRead = Math.min(
          len - Math.max(0, numRead),
          tail.length - tailIdx);
      System.arraycopy(this.tail, tailIdx, cbuf, off + Math.max(0, numRead), toRead);
      this.tailIdx += toRead;
      if (numRead < 0) {
        numRead = 0;
      }
      numRead += toRead;
    }
    return numRead;
  }
}
