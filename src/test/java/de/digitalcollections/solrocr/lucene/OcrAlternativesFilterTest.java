package de.digitalcollections.solrocr.lucene;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.common.collect.Range;
import de.digitalcollections.solrocr.formats.OcrParser;
import de.digitalcollections.solrocr.lucene.filters.OcrCharFilter;
import de.digitalcollections.solrocr.reader.PeekingReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.core.UnicodeWhitespaceTokenizer;
import org.apache.lucene.analysis.core.WhitespaceTokenizer;
import org.apache.lucene.analysis.icu.segmentation.ICUTokenizer;
import org.apache.lucene.analysis.standard.StandardTokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;

public class OcrAlternativesFilterTest {
  public static Stream<Class<? extends Tokenizer>> getTokenizers() {
    return Stream.of(
        StandardTokenizer.class,
        UnicodeWhitespaceTokenizer.class,
        WhitespaceTokenizer.class,
        ICUTokenizer.class
    );
  }

  @ParameterizedTest
  @MethodSource("getTokenizers")
  public void testAlternativesSurviveTokenizer(Class<? extends Tokenizer> tokenizerCls) throws Exception {
    Tokenizer tokenizer = tokenizerCls.getDeclaredConstructor().newInstance();
    tokenizer.setReader(new StubOcrCharFilter(
        "YoB\u2060\u2060123⁠⁠OB Greene pur-chased\u2060\u2060223⁠⁠"
        + "pure-based\u2060\u2060323⁠⁠pUl-cohased\u2060\u2060423⁠⁠pure.bred of Ben F Mark 40"
        + " cattle\u2060\u2060523⁠⁠cattlc"));
    TokenFilter filter = new OcrAlternativesFilterFactory.OcrAlternativesFilter(tokenizer);
    List<String> tokens = new ArrayList<>();
    List<Integer> positionIncrements = new ArrayList<>();
    List<Integer> startOffsets = new ArrayList<>();
    filter.reset();
    while (filter.incrementToken()) {
      CharTermAttribute charAttr = filter.getAttribute(CharTermAttribute.class);
      tokens.add(new String(charAttr.buffer(), 0, charAttr.length()));
      positionIncrements.add(filter.getAttribute(PositionIncrementAttribute.class).getPositionIncrement());
      startOffsets.add(filter.getAttribute(OffsetAttribute.class).startOffset());
    }
    filter.end();
    filter.close();
    if (tokenizer instanceof StandardTokenizer || tokenizer instanceof ICUTokenizer) {
      assertThat(tokens).containsExactly(
          "YoB", "OB", "Greene", "pur", "chased", "of", "Ben", "F", "Mark", "40", "cattle", "cattlc");
      assertThat(positionIncrements).containsExactly(
          1,    0,        1,     1,        1,    1,     1,   1,      1,    1,        1,        0);
      assertThat(startOffsets).containsExactly(
          0,  123,       13,    20,       24,   82,    85,  89,     91,   96,       99,      523);
    } else {
      assertThat(tokens).containsExactly(
          "YoB", "OB", "Greene", "pur-chased", "pure-based", "pUl-cohased", "pure.bred", "of", "Ben", "F", "Mark", "40",
          "cattle", "cattlc");
      assertThat(positionIncrements).containsExactly(
             1,    0,        1,            1,            0,             0,           0,    1,     1,   1,      1,    1,
                1,        0);
      assertThat(startOffsets).containsExactly(
             0,  123,       13,           20,          223,           323,         423,   82,    85,  89,     91,   96,
               99,      523);
    }
  }

  @SuppressWarnings("UnstableApiUsage")
  public static class StubOcrCharFilter extends OcrCharFilter {

    public StubOcrCharFilter(String filteredStream) {
      super(
          Mockito.when(Mockito.mock(OcrParser.class).getInput()).thenReturn(
          new PeekingReader(new StringReader(filteredStream), 2048, 16384)).getMock());
      this.alternativeMap.put(
          Range.closedOpen(0, 12),
          new TokenWithAlternatives(0, 3, 2));
      this.alternativeMap.put(
          Range.closedOpen(20, 81),
          new TokenWithAlternatives(20, 30, 4));
      this.alternativeMap.put(
          Range.closedOpen(99, 118),
          new TokenWithAlternatives(99, 105, 2));
    }

    @Override
    public int read(char[] cbuf, int off, int len) {
      try {
        return this.input.read(cbuf, off, len);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }
  }
}
