package de.digitalcollections.solrocr.lucene;

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
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;

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
    tokenizer.setReader(new StringReader(
        "YoB\u2060\u2060OB Greene purchased\u2060\u2060"
        + "purebased\u2060\u2060pUlcohased\u2060\u2060purebred of Ben F Mark 40"
        + " cattle\u2060\u2060cattlc"));
    TokenFilter filter = new OcrAlternativesFilterFactory.OcrAlternativesFilter(tokenizer);
    List<String> tokens = new ArrayList<>();
    List<Integer> positionIncrements = new ArrayList<>();
    filter.reset();
    while (filter.incrementToken()) {
      CharTermAttribute charAttr = filter.getAttribute(CharTermAttribute.class);
      tokens.add(new String(charAttr.buffer(), 0, charAttr.length()));
      positionIncrements.add(filter.getAttribute(PositionIncrementAttribute.class).getPositionIncrement());
    };
    filter.end();
    filter.close();
    assertThat(tokens).containsExactly(
        "YoB", "OB", "Greene", "purchased", "purebased", "pUlcohased", "purebred", "of", "Ben", "F", "Mark", "40",
        "cattle", "cattlc");
    assertThat(positionIncrements).containsExactly(1, 0, 1, 1, 0, 0, 0, 1, 1, 1, 1, 1, 1, 0);
  }
}
