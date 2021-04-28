package de.digitalcollections.solrocr.iter;

/** A {@link BreakLocator} that splits an XML-like document on a specific opening or closing tag. */
public class TagBreakLocator extends BaseBreakLocator {
  private final String breakTag;

  public TagBreakLocator(IterableCharSequence text, String tagName) {
    this(text, tagName, false);
  }

  public TagBreakLocator(IterableCharSequence text, String tagName, boolean closing) {
    super(text);
    if (closing) {
      this.breakTag = ("</" + tagName + ">"); // .toCharArray();
    } else {
      this.breakTag = ("<" + tagName); // .toCharArray();
    }
  }

  @Override
  protected int getFollowing(int offset) {
    int blockSize = 65536;
    int overlap = this.breakTag.length();
    int start = Math.min(offset + 1, this.text.getEndIndex());
    int end = Math.min(start + blockSize, this.text.getEndIndex());
    while (start < this.text.getEndIndex()) {
      String block = text.subSequence(start, end, true).toString();
      int idx = block.indexOf(breakTag);
      if (idx >= 0) {
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
  protected int getPreceding(int offset) {
    int blockSize = 65536;
    int overlap = this.breakTag.length();
    int end = Math.max(0, offset - 1);
    int start = Math.max(0, end - blockSize);
    while (start >= this.text.getBeginIndex()) {
      String block = text.subSequence(start, end, true).toString();
      int idx = optimizedLastIndexOf(block, breakTag, block.length());
      if (idx >= 0) {
        return start + idx;
      }
      end = start + overlap;
      if (start == 0) {
        break;
      }
      start = Math.max(0, start - blockSize);
    }
    return this.text.getBeginIndex();
  }
}
