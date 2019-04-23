package org.mdz.search.solrocr.formats.hocr;

import com.google.common.collect.ImmutableSet;
import java.text.BreakIterator;
import java.text.CharacterIterator;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class HocrClassBreakIterator extends BreakIterator {
  private final static Pattern CLASS_PAT = Pattern.compile("class=['\"](?<class>ocr.+?)['\"]");
  private final Set<String> breakClasses;

  private CharacterIterator text;
  private int current;

  public HocrClassBreakIterator(String breakClass) {
    this.breakClasses = ImmutableSet.of(breakClass);
  }

  public HocrClassBreakIterator(Set<String> breakClasses) {
    this.breakClasses = breakClasses;
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
    for (int i=n; i > 0; i++) {
      this.next();
    }
    return this.current;
  }

  @Override
  public int next() {
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
        this.current = this.text.getIndex();;
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
    String fullTag = "";
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
