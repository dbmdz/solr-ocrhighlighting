package de.digitalcollections.solrocr.lucene.filters;

import com.google.common.collect.Range;
import com.google.common.collect.RangeMap;
import com.google.common.collect.TreeRangeMap;
import de.digitalcollections.solrocr.formats.OcrParser;
import de.digitalcollections.solrocr.model.OcrBox;
import java.io.StringReader;
import java.util.List;
import java.util.Optional;
import org.apache.lucene.analysis.CharFilter;
import org.apache.lucene.analysis.charfilter.BaseCharFilter;

@SuppressWarnings("UnstableApiUsage")
public class OcrCharFilter extends BaseCharFilter {
  private final OcrParser parser;
  protected final RangeMap<Integer, TokenWithAlternatives> alternativeMap = TreeRangeMap.create();

  private char[] curWord;
  private int curWordIdx = -1;
  private int outputOffset = 0;

  public static OcrCharFilter nopFilter() {
    return new OcrCharFilter();
  }

  private OcrCharFilter() {
    super(new StringReader(""));
    this.parser = null;
  }

  public OcrCharFilter(OcrParser parser) {
    super(parser.getInput());
    this.parser = parser;
  }

  private void readNextWord() {
    while (this.curWord == null && this.parser.hasNext()) {
      OcrBox nextWord = this.parser.next();
      if (nextWord.getText() == null) {
        continue;
      }

      // For hyphenated words where both the hyphen start and the end word are next to each
      // other, we only index the dehyphenated content and the trailing chars of the hyphen end.
      boolean wordIsCompleteHyphenation =
          (nextWord.isHyphenated()
              && this.parser
                  .peek()
                  .filter(b -> b.isHyphenated() && !b.isHyphenStart())
                  .isPresent());
      if (wordIsCompleteHyphenation) {
        String text = nextWord.getDehyphenatedForm();
        int offset = nextWord.getTextOffset();
        int beginLength = nextWord.getText().length();
        if (nextWord.getText().endsWith("-") && text.indexOf(nextWord.getText()) != 0) {
          // In the case where the hyphen is part of the word and not an extra char
          beginLength -= 1;
        }
        int endOutputOffset = outputOffset + beginLength;
        OcrBox hyphenEnd = this.parser.next();
        int endOffset = hyphenEnd.getTextOffset();
        if (hyphenEnd.getTrailingChars() != null) {
          text += hyphenEnd.getTrailingChars();
        }
        this.curWord = text.toCharArray();
        this.curWordIdx = 0;
        // Map the offsets correctly: We output the full dehyphenated form, but the offsets point to
        // the constituting parts, i.e. the beginning and end text. This only makes a difference for
        // ALTO.
        this.addOffCorrectMap(outputOffset, offset - outputOffset);
        this.addOffCorrectMap(endOutputOffset, endOffset - endOutputOffset);
        break;
      }

      this.addOffCorrectMap(outputOffset, nextWord.getTextOffset() - outputOffset);

      StringBuilder text = new StringBuilder(nextWord.getText());
      if (!nextWord.getAlternatives().isEmpty()) {
        List<String> alts = nextWord.getAlternatives();
        int wordIdx = text.length();
        for (int i = 0; i < alts.size(); i++) {
          // Every alternative is preceded a sequence of `<marker><offset><marker>`. The markers are
          // sequences of unicode `WORD JOINER` characters that prevent tokenizers from separating
          // alternatives and their offsets from each other so they can be accessed as a single unit
          // downstream in the `OcrAlternativesFilter`.
          text.append(OcrCharFilterFactory.ALTERNATIVE_MARKER);
          int offset;
          if (this.input instanceof CharFilter) {
            offset =
                ((CharFilter) this.input).correctOffset(nextWord.getAlternativeOffsets().get(i));
          } else {
            offset = nextWord.getAlternativeOffsets().get(i);
          }
          text.append(offset);
          text.append(OcrCharFilterFactory.ALTERNATIVE_MARKER);
          int markerLen =
              OcrCharFilterFactory.ALTERNATIVE_MARKER.length() * 2
                  + Integer.toString(offset).length();
          wordIdx += markerLen;
          int outOff = this.outputOffset + wordIdx;
          this.addOffCorrectMap(outOff, nextWord.getAlternativeOffsets().get(i) - outOff);
          text.append(alts.get(i));
          wordIdx += alts.get(i).length();
        }
        alternativeMap.put(
            Range.closedOpen(
                this.correctOffset(outputOffset), this.correctOffset(outputOffset + wordIdx)),
            new TokenWithAlternatives(
                this.correctOffset(outputOffset),
                this.correctOffset(outputOffset + text.length()),
                1 + alts.size()));
        if ((nextWord.isHyphenStart() == null || !nextWord.isHyphenStart())
            && !nextWord.getTrailingChars().contains(" ")) {
          // Add a whitespace after boxes with alternatives so the tokenizer doesn't munge
          // together the last alternative with the following token
          nextWord.setTrailingChars(nextWord.getTrailingChars() + " ");
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
    if (this.parser == null || !this.parser.hasNext()) {
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

  public Optional<TokenWithAlternatives> getTokenWithAlternatives(int inputOffset) {
    return Optional.ofNullable(this.alternativeMap.get(inputOffset));
  }

  public static class TokenWithAlternatives {
    public final int defaultFormStart;
    public final int defaultFormEnd;
    public final int numForms;

    public TokenWithAlternatives(int defaultFormStart, int defaultFormEnd, int numForms) {
      this.defaultFormStart = defaultFormStart;
      this.defaultFormEnd = defaultFormEnd;
      this.numForms = numForms;
    }

    @Override
    public String toString() {
      return String.format(
          "TokenWithAlternatives{%d@[%d:%d[}", numForms, defaultFormStart, defaultFormEnd);
    }
  }
}
