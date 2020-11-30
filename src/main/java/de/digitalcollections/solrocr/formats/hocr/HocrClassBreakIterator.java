package de.digitalcollections.solrocr.formats.hocr;

import com.google.common.collect.ImmutableSet;
import de.digitalcollections.solrocr.iter.IterableCharSequence;
import java.text.BreakIterator;
import java.text.CharacterIterator;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class HocrClassBreakIterator extends BreakIterator {
  private final static Pattern CLASS_PAT = Pattern.compile("class=['\"](?<class>ocr.+?)['\"]");
  private final Set<String> breakClasses;
  private int overlap;

  private CharacterIterator text;
  private int current;

  public HocrClassBreakIterator(String breakClass) {
    this.breakClasses = ImmutableSet.of(breakClass);
  }

  public HocrClassBreakIterator(Set<String> breakClasses) {
    this.breakClasses = breakClasses;
    this.overlap = breakClasses.stream()
        .map(String::length)
        .max(Integer::compareTo)
        .map(size -> size + 128)
        .orElseThrow(() -> new RuntimeException("No break classes supplied."));
  }

  @Override
  public int first() {
    this.text.first();
    this.current = this.text.getIndex();
    return this.current();
  }

  @Override
  public int last() {
    this.text.last();
    this.current = this.text.getIndex();
    return this.current();
  }

  @Override
  public int next(int n) {
    for (int i=n; i > 0; i--) {
      this.next();
    }
    return this.current;
  }

  public int nextBuffered() {
    int blockSize = 65536;
    IterableCharSequence seq = (IterableCharSequence) this.text;
    int textIdx = this.text.getIndex();
    int start = textIdx;
    int end = Math.min(textIdx + blockSize, this.text.getEndIndex());
    while (start < this.text.getEndIndex()) {
      String block = seq.subSequence(start, end, true).toString();
      // Truncate block to last '>' to avoid splitting element openings across blocks
      int lastTagClose = block.lastIndexOf('>');
      if (lastTagClose > 0) {
        block = block.substring(0, lastTagClose + 1);
        end = start + lastTagClose;
      }
      int idx = block.length();
      int closeIdx = block.length() - 1;
      boolean keepGoing = true;
      int fromIdx = 0;
      outer:
      while (keepGoing) {
        for (String breakClass : this.breakClasses) {
          int i = block.indexOf(breakClass, fromIdx);
          if (i < 0) {
            keepGoing = false;
            continue;
          }
          closeIdx = block.indexOf('>', i);
          if (closeIdx > block.indexOf('<', i)) {
            // Not inside of an element tag
            continue;
          }
          int elemOpen = block.lastIndexOf('<', i);
          if (block.startsWith("meta", elemOpen + 1)) {
            // Block specification in meta tag, not a real block
            fromIdx = closeIdx;
            continue outer;
          }
          keepGoing = false;
          if (elemOpen >= 0 && elemOpen < idx) {
            idx = elemOpen;
          }
        }
      }
      if (idx != block.length()) {
        if (closeIdx < idx) {
          closeIdx = block.length();
        }
        this.text.setIndex(Math.min(start + closeIdx, this.text.getEndIndex()));
        return start + idx;
      }

      start = end;
      if (end < this.text.getEndIndex()) {
        end -= overlap;
      }
      end = Math.min(end + blockSize, this.text.getEndIndex());
    }
    return this.text.getEndIndex();
  }

  @Override
  public int next() {
    if (this.text instanceof IterableCharSequence) {
      this.current = this.nextBuffered();
      return this.current;
    }
    String fullTag = "";
    String hocrClass = "";
    StringBuilder sb = null;
    while(!breakClasses.contains(hocrClass)) {
      char c = this.text.current();
      if (c == '<') {
        sb = new StringBuilder();
      }
      if (sb != null) {
        sb.append(c);
        if (c == '>') {
          fullTag = sb.toString();
          hocrClass = getHocrClass(fullTag);
          sb = null;
        }
      }
      if (this.text.next() == CharacterIterator.DONE) {
        this.current = this.text.getIndex();
        return this.current;
      }
    }
    // FIXME: This will break with ByteCharIterators if the tag has a multi-byte codepoint.
    this.current = this.text.getIndex() - fullTag.length();
    return this.current;
  }

  private String getHocrClass(String fullTag) {
    Matcher m = CLASS_PAT.matcher(fullTag);
    if (m.find()) {
      return m.group("class");
    } else {
      return "";
    }
  }

  @Override
  public int previous() {
    if (this.text instanceof IterableCharSequence) {
      this.current = this.previousBuffered();
      this.text.setIndex(this.current);
      return this.current;
    }

    String fullTag;
    String hocrClass = "";
    StringBuilder sb = null;
    while(!breakClasses.contains(hocrClass)) {
      char c = this.text.current();
      if (c == '>') {
        sb = new StringBuilder();
      }
      if (sb != null) {
        sb.insert(0, c);
        if (c == '<') {
          fullTag = sb.toString();
          hocrClass = getHocrClass(fullTag);
          sb = null;
        }
      }
      if (this.text.previous() == CharacterIterator.DONE) {
        this.current = this.text.getIndex();
        return this.current;
      }
    }
    // FIXME: This will break with ByteCharIterators if the tag has a multi-byte codepoint.
    this.current = this.text.getIndex() + 1;
    return this.current;
  }

  private int previousBuffered() {
    int blockSize = 65536;
    IterableCharSequence seq = (IterableCharSequence) this.text;
    int textIdx = this.text.getIndex();
    int end = textIdx;
    int start = Math.max(0, textIdx - blockSize);
    while (start >= this.text.getBeginIndex()) {
      String block = seq.subSequence(start, end, true).toString();
      int firstTagOpen = block.indexOf('<');
      if (firstTagOpen > 0) {
        block = block.substring(firstTagOpen);
        start += firstTagOpen;
      }
      int idx = -1;
      boolean keepGoing = true;
      int toIdx = block.length();
      outer:
      while (keepGoing) {
        for (String breakClass : this.breakClasses) {
          int i = block.lastIndexOf(breakClass, toIdx);
          if (i < 0) {
            keepGoing = false;
            continue;
          }
          int elemOpen = block.lastIndexOf('<', i);
          int closeIdx = block.lastIndexOf('>', i);
          if (elemOpen < closeIdx) {
            // Not inside of an element tag
            continue;
          }
          if (block.startsWith("meta", elemOpen + 1)) {
            toIdx = elemOpen;
            continue outer;
          }
          keepGoing = false;
          if (elemOpen > idx) {
            idx = elemOpen;
          }
        }
      }
      if (idx >= 0) {
        return start + idx;
      }

      if (start == 0) {
        break;
      }
      end = start + overlap;
      start = Math.max(0, start - blockSize);
    }
    return this.text.getBeginIndex();
  }

  @Override
  public int following(int offset) {
    this.text.setIndex(offset);
    return this.next();
  }

  @Override
  public int preceding(int offset) {
    this.text.setIndex(offset);
    return this.previous();
  }

  @Override
  public int current() {
    return this.current;
  }

  @Override
  public CharacterIterator getText() {
    return this.text;
  }

  @Override
  public void setText(CharacterIterator newText) {
    this.text = newText;
  }
}
