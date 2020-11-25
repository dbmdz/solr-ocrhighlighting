package de.digitalcollections.solrocr.lucene.filters;

import de.digitalcollections.solrocr.formats.OcrParser;
import de.digitalcollections.solrocr.model.OcrBox;
import java.util.List;
import org.apache.lucene.analysis.charfilter.BaseCharFilter;

public class OcrCharFilter extends BaseCharFilter {
  private final OcrParser parser;

  private char[] curWord;
  private int curWordIdx = -1;
  private int outputOffset = 0;

  public OcrCharFilter(OcrParser parser) {
    super(parser.getInput());
    this.parser = parser;
  }

  private void readNextWord() {
    while (this.curWord == null && this.parser.hasNext()) {
      OcrBox nextWord = this.parser.next();

      if (nextWord.isHyphenated()) {
        String text = nextWord.getDehyphenatedForm();
        OcrBox hyphenEnd = this.parser.next();
        if (hyphenEnd.getTrailingChars() != null) {
          text += hyphenEnd.getTrailingChars();
        }
        this.curWord = text.toCharArray();
        this.curWordIdx = 0;
        if (nextWord.getDehyphenatedOffset() != null) {
          this.addOffCorrectMap(outputOffset, nextWord.getDehyphenatedOffset() - outputOffset);
        } else {
          this.addOffCorrectMap(outputOffset, nextWord.getTextOffset() - outputOffset);
        }
        continue;
      }

      this.addOffCorrectMap(outputOffset, nextWord.getTextOffset() - outputOffset);

      StringBuilder text = new StringBuilder(nextWord.getText());
      if (!nextWord.getAlternatives().isEmpty()) {
        List<String> alts = nextWord.getAlternatives();
        int wordIdx = text.length();
        for (int i=0; i < alts.size(); i++) {
          text.append(OcrCharFilterFactory.ALTERNATIVE_MARKER);
          wordIdx += OcrCharFilterFactory.ALTERNATIVE_MARKER.length();
          int outOff = this.outputOffset + wordIdx;
          this.addOffCorrectMap(outOff, nextWord.getAlternativeOffsets().get(i) - outOff);
          text.append(alts.get(i));
          wordIdx += alts.get(i).length();
        }
      }
      if (nextWord.getTrailingChars() != null) {
        text.append(nextWord.getTrailingChars());
      }
      this.curWord = text.toString().toCharArray();
      this.curWordIdx = 0;
    }
  }

  @Override
  public int read(char[] cbuf, int off, int len) {
    if (!this.parser.hasNext()) {
      return -1;
    }

    if (this.curWord == null) {
      this.readNextWord();
    }

    int numRead = 0;
    while (numRead < len && (this.curWord != null)) {
      int lenToRead = Math.min(len - numRead, this.curWord.length - this.curWordIdx);
      System.arraycopy(this.curWord, this.curWordIdx, cbuf, off + numRead, lenToRead);
      curWordIdx += lenToRead;
      outputOffset += lenToRead;
      numRead += lenToRead;
      if (curWordIdx == curWord.length) {
        this.curWord = null;
      }
    }
    return numRead;
  }
}
