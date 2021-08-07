package de.digitalcollections.solrocr.lucene.filters;

import com.google.common.collect.ImmutableSet;
import de.digitalcollections.solrocr.util.SourceAwareReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.lucene.analysis.charfilter.BaseCharFilter;

/**
 * Character filter that "sanitizes" malformed input XML by inserting closing tags for unmatched
 * opening tags and dropping unmatched closing tags, while maintaining the input offsets.
 *
 * <p>We have to deal with malformed XML, since we allow users to index relatively arbitrary regions
 * from input files and also concatenate them with each other, resulting in some unmatched
 * opening/ending tags. This didn't use to be a problem since we used a RegEx-based parsing
 * approach, but with the new StAX-based approach we have to make sure that the XML we feed to the
 * parser is well-formed.
 */
public class SanitizingXmlFilter extends BaseCharFilter implements SourceAwareReader {

  private static final Set<String> STRIP_TAGS = ImmutableSet.of("br");
  private final Deque<char[]> elementStack = new ArrayDeque<>();
  private char[] carryOver = null;
  private int carryOverIdx = -1;
  private char[] tail = null;
  private int tailIdx = -1;
  private boolean hasDocType = false;
  private final boolean advancedFixing;

  public SanitizingXmlFilter(Reader in, boolean advancedFixing) {
    super(in);
    this.advancedFixing = advancedFixing;
  }

  boolean tagEquals(char[] markupBuf, int startTag, int tagLen, char[] checkTag) {
    if (tagLen != checkTag.length) {
      return false;
    }
    for (int i = 0; i < tagLen; i++) {
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

    outer:
    while (idx < (off + numRead)) {
      // Check for invalid entities and try to fix them
      while (advancedFixing && idx < (off + numRead)) {
        int match = multiIndexOf(cbuf, idx, '<', '&');
        if (match < 0 || match > (off + numRead)) {
          // Nothing to do in this buffer
          break outer;
        }
        if (cbuf[match] == '<') {
          // Start of element, no more entitites to check
          break;
        }
        int entityEnd = multiIndexOf(cbuf, match + 1, '<', ';');
        if (entityEnd < 0 || entityEnd == match + 1 || cbuf[entityEnd] == '<') {
          // Illegal entity declaration (doesn't end before next element opening), mask
          cbuf[match] = '_';
        }
        idx = match + 1;
      }
      int startElem = ArrayUtils.indexOf(cbuf, '<', idx);
      if (startElem < 0 || startElem > (off + numRead)) {
        break;
      }
      if (advancedFixing) {
        int nextOpen = ArrayUtils.indexOf(cbuf, '<', startElem + 1);
        int nextClose = ArrayUtils.indexOf(cbuf, '>', startElem);
        if (nextOpen >= 0 && nextOpen < nextClose) {
          // Isolated opening pointy bracket, is illegal XML, but can happen in some bad hOCR
          cbuf[startElem] = '_';
          idx += 1;
          continue;
        }
      }
      int endElem = ArrayUtils.indexOf(cbuf, '>', startElem + 1);
      if (endElem < 0 || endElem > (off + numRead)) {
        // End of element is not part of this buffer, reduce read size so the element is fully part
        // of the next read
        truncated = true;
        this.carryOver = new char[numRead - (startElem - off)];
        System.arraycopy(cbuf, startElem, this.carryOver, 0, this.carryOver.length);
        this.carryOverIdx = 0;
        numRead -= this.carryOver.length;
        break;
      }
      idx = endElem + 1;

      if (advancedFixing) {
        if (cbuf[startElem + 1] == '?' && (endElem - startElem < 3 || cbuf[endElem - 1] != '?')) {
          // Illegal processing instruction, fix by stripping the question mark
          cbuf[startElem + 1] = '_';
        }

        if (cbuf[startElem + 1] == '!') {
          boolean illegal = (
              // Comment?
              (cbuf[startElem + 2] == '-' && cbuf[startElem + 3] != '-')
                  // Doctype?
                  || ((cbuf[startElem + 2] == 'D' || cbuf[startElem + 2] == 'd')
                      && (endElem - startElem) < 12)
                  // CDATA?
                  || (cbuf[startElem + 2] == '[' && (endElem - startElem) < 10));
          if (illegal) {
            cbuf[startElem] = '_';
            cbuf[endElem] = '-';
            continue;
          }
        }

        if (cbuf[startElem + 1] == '?'
            || (cbuf[startElem + 1] == '!' && cbuf[startElem + 2] == '-')
                && cbuf[startElem + 3] == '-') {
          // XML Declaration or comment, nothing to do
          continue;
        }
      } else if (cbuf[startElem + 1] == '?'
          || (cbuf[startElem + 1] == '!' && cbuf[startElem + 2] == '-')) {
        // XML Declaration or comment, nothing to do
        continue;
      }

      // Strip out duplicate doctype declarations, these break the multidoc parsing mode in Woodstox
      if (cbuf[startElem + 1] == '!'
          && (cbuf[startElem + 2] == 'D' || cbuf[startElem + 2] == 'd')) {
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

      if (advancedFixing) {
        // Check if we're dealing with a legal tag, in some early Google Books hOCR unescaped
        // `<` and `>` characters sometimes lead to spans that look like elements but aren't
        // actually
        boolean illegalTag = tagLen == 0;
        for (int i = 0; i < tagLen; i++) {
          if (!Character.isLetter(cbuf[startTag + i]) && cbuf[startTag + i] != ':') {
            illegalTag = true;
            break;
          }
        }
        if (illegalTag) {
          cbuf[startElem] = '_';
          cbuf[endElem] = '_';
          continue;
        }
      }

      if (cbuf[startElem + 1] == '/') {
        char[] checkTag = elementStack.peek();
        if (checkTag == null || !tagEquals(cbuf, startTag, tagLen, checkTag)) {
          // Closing tag doesn't match last opened tag, "zero out" the tag by replacing it with
          // whitespace. Yeah, this is pure cheating, a proper CharFilter implementation would strip
          // those chars from the output, but this would require more copying around of data, and we
          // normalize whitespace anyway down the line, so this isn't really an issue for now
          // (I think....)
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
        if (STRIP_TAGS.contains(new String(newTag))) {
          for (int i = startElem; i <= endElem; i++) {
            cbuf[i] = ' ';
          }
        } else {
          elementStack.push(newTag);
        }
      }
    }
    if (!truncated && numRead < len && !elementStack.isEmpty()) {
      if (this.tail == null) {
        this.tail =
            elementStack.stream()
                .map(tag -> "</" + new String(tag) + ">")
                .collect(Collectors.joining(""))
                .toCharArray();
        this.tailIdx = 0;
      } else if (this.tailIdx == this.tail.length) {
        return -1;
      }

      int toRead = Math.min(len - Math.max(0, numRead), tail.length - tailIdx);
      System.arraycopy(this.tail, tailIdx, cbuf, off + Math.max(0, numRead), toRead);
      this.tailIdx += toRead;
      if (numRead < 0) {
        numRead = 0;
      }
      numRead += toRead;
    }
    return numRead;
  }

  /**
   * Variant of {@link org.apache.commons.lang3.ArrayUtils#indexOf(char[], char)} that supports
   * looking for multiple values.
   */
  private static int multiIndexOf(final char[] array, int startIndex, final char... valuesToFind) {
    if (array == null) {
      return -1;
    }
    if (startIndex < 0) {
      startIndex = 0;
    }
    for (int i = startIndex; i < array.length; i++) {
      for (char value : valuesToFind) {
        if (value == array[i]) {
          return i;
        }
      }
    }
    return -1;
  }

  @Override
  public Optional<String> getSource() {
    if (this.input instanceof SourceAwareReader) {
      return ((SourceAwareReader) this.input).getSource();
    } else {
      return Optional.empty();
    }
  }
}
