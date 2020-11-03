package de.digitalcollections.solrocr.lucene;

import de.digitalcollections.solrocr.lucene.filters.OcrCharFilterFactory;
import de.digitalcollections.solrocr.util.CharBufUtils;
import java.io.IOException;
import java.util.Map;
import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.apache.lucene.analysis.util.TokenFilterFactory;

/** Token Filter that indexes alternative readings parsed from OCR files.
 *
 * Requires that the {@code expandAlternatives} attribute is set to {@code true} on the {@link OcrCharFilterFactory}
 * in the field type's analysis chain.
 *
 * <p><strong>You cannot use the {@link org.apache.lucene.analysis.standard.ClassicTokenizer} with this filter</strong>,
 * since it splits tokens on the U+2060 (Word Joiner) codepoint, contrary to Unicode rules. Please use one of the newer,
 * unicode-compliant tokenizers like {@link org.apache.lucene.analysis.standard.StandardTokenizer}
 *
 * <p><strong>Make sure that the chosen tokenizer does not split words on the U+2090 (Word Joiner) codepoint, this filter
 * uses it to detect alternatives for a given token.</strong> This is the case for almost all built-in tokenizers of
 * Lucene/Solr (except for the {@link org.apache.lucene.analysis.standard.ClassicTokenizer}, see above, so this warning
 * is primarily targeted at users with custom tokenizers.
 *
 * <pre class="prettyprint">
 * &lt;fieldType name="text_ocr" class="solr.TextField"&gt;
 *   &lt;analyzer&gt;
 *     &lt;charFilter class="de.digitalcollections.solrocr.lucene.filters.ExternalUtf8ContentFilterFactory"/&gt;
 *     &lt;charFilter
 *       class="de.digitalcollections.solrocr.lucene.filters.OcrCharFilterFactory"
 *       expandAlternatives="true"
 *     /&gt;
 *     &lt;tokenizer class="solr.StandardTokenizerFactory"/&gt;
 *     &lt;filter class="de.digitalcollections.solrocr.lucene.OcrAlternativesFilterFactory"/&lt;
 *     &lt;filter class="solr.LowerCaseFilterFactory"/&gt;
 *   &lt;/analyzer&gt;
 * &lt;/fieldType&gt;
 * </pre>
 */
public class OcrAlternativesFilterFactory extends TokenFilterFactory {
  public OcrAlternativesFilterFactory(Map<String, String> args) {
    super(args);
  }

  @Override
  public TokenStream create(TokenStream input) {
    return new OcrAlternativesFilter(input);
  }

  public static class OcrAlternativesFilter extends TokenFilter {
    private static final char[] ALTERNATIVE_MARKER = OcrCharFilterFactory.ALTERNATIVE_MARKER.toCharArray();

    private final CharTermAttribute termAtt = this.addAttribute(CharTermAttribute.class);
    private final PositionIncrementAttribute posIncAtt = this.addAttribute(PositionIncrementAttribute.class);

    /** Recorded token state, largely re-used for every alternative */
    private State state = null;

    /** Buffer with the chars of the current term */
    private char[] curTermBuffer;

    /** Length of the current term, every char after this offset in `curTermBuffer` is garbage. */
    private int curTermLength;

    /** Offset in the term buffer until which we've already outputted all alternatives.
     *  When this is equal to `curTermLength`, we're finished with the token.
     */
    private int curPos;

    protected OcrAlternativesFilter(TokenStream input) {
      super(input);
    }

    @Override
    public final boolean incrementToken() throws IOException {
      // Initialize variable to hold the offset of the next alternative in the term buffer
      int nextAlternativeIdx = -1;
      if (curTermBuffer == null) {
        if (!this.input.incrementToken()) {
          return false;
        }

        // Check if the current token has any alternatives
        nextAlternativeIdx = CharBufUtils.indexOf(
            termAtt.buffer(), 0, termAtt.length(), ALTERNATIVE_MARKER);
        if (nextAlternativeIdx < 0) {
          return true;
        }
        // Record the state so we can access it during `incrementToken`
        this.state = this.captureState();

        // Set the initial token state
        this.curTermBuffer = this.termAtt.buffer().clone();
        this.curTermLength = this.termAtt.length();
        this.curPos = 0;
      }

      // This will only be smaller than zero if we're on a token's second form/first alternative
      if (nextAlternativeIdx < 0) {
        // Restore all attributes for the token so the alternative has the same attributes, except for the characters
        this.restoreState(this.state);
        nextAlternativeIdx = CharBufUtils.indexOf(
            this.curTermBuffer, this.curPos, curTermLength, ALTERNATIVE_MARKER);
      }
      // Change the term attribute to contain the current alternative
      int end = nextAlternativeIdx >= 0 ? nextAlternativeIdx : curTermLength;
      this.termAtt.copyBuffer(this.curTermBuffer, curPos, end - curPos);

      if (curPos > 0) {
        // Every alternative is at the same position as the original token
        this.posIncAtt.setPositionIncrement(0);
      }
      if (end == curTermLength) {
        // We're done with this token's alternatives, reset term state
        this.curTermBuffer = null;
        this.curTermLength = -1;
        this.curPos = -1;
      } else {
        // Update term buffer pointer to point at the next alternative
        this.curPos = nextAlternativeIdx + ALTERNATIVE_MARKER.length;
      }
      return true;
    }
  }
}
