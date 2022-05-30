package solrocr;

import com.github.dbmdz.solrocr.lucene.filters.OcrCharFilter;
import com.github.dbmdz.solrocr.util.CharBufUtils;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.util.Map;
import java.util.Optional;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenFilterFactory;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Token Filter that indexes alternative readings parsed from OCR files.
 *
 * <p>Requires that the {@code expandAlternatives} attribute is set to {@code true} on the {@link
 * OcrCharFilterFactory} in the field type's analysis chain.
 *
 * <p><strong>Note that alternatives that are split by the tokenizer are ignored.</strong> This
 * happens e.g. when you have a token like {@code pur-chased} with an alternative {@code
 * pure-based}: The {@code StandardTokenizer} will split at the hyphen and thus sever the connection
 * between the alternatives. To avoid this problem, consider using a simpler tokenizer that doesn't
 * split on hyphens (e.g. {@code UnicodeWhitespaceTokenizer} or {@code WhitespaceTokenizer}) or
 * customize the {@code StandardTokenizer} to not split inside of tokens that have alternatives.
 *
 * <p><strong>This filter factory needs to be placed after a {@code TokenizerFactory} whose input is
 * an instance of {@code OcrCharFilterFactory}.</strong>
 *
 * <p><strong>Consider increasing {@code maxTokenLength} for your tokenizer.</strong> By default,
 * many of Solr's built-in tokenizers will truncate tokens to 255 characters, which can lead to
 * truncated and missing alternatives. You will see a warning in the logs when this happens,
 * increasing this value to 512 or 1024 in the config will fix this, at the expense of an increase
 * in memory usage during indexing.
 *
 * <p><strong>You cannot use the {@link org.apache.lucene.analysis.classic.ClassicTokenizer} with
 * this filter</strong>, since it splits tokens on the U+2060 (Word Joiner) codepoint, contrary to
 * Unicode rules. Please use one of the newer, unicode-compliant tokenizers like {@link
 * org.apache.lucene.analysis.standard.StandardTokenizer}.
 *
 * <p><strong>When using a custom tokenizer, make sure that it also does not split words on the
 * U+2060 (Word Joiner) codepoint, this filter uses it to detect alternatives for a given
 * token.</strong>
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
    private static final char[] ALTERNATIVE_MARKER =
        OcrCharFilterFactory.ALTERNATIVE_MARKER.toCharArray();

    private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
    private final CharTermAttribute termAtt = this.addAttribute(CharTermAttribute.class);
    private final PositionIncrementAttribute posIncAtt =
        this.addAttribute(PositionIncrementAttribute.class);
    private final OffsetAttribute offsetAtt = this.addAttribute(OffsetAttribute.class);

    /**
     * The currently active input OcrCharFilter instance.
     *
     * <p>Used to check whether a given token is part of a multi-term alternative and should be
     * ignored.
     */
    private OcrCharFilter inputFilter = null;

    /** Recorded token state, largely re-used for every alternative */
    private State state = null;

    /** Buffer with the chars of the current term */
    private char[] curTermBuffer;

    /** Length of the current term, every char after this offset in `curTermBuffer` is garbage. */
    private int curTermLength;

    /**
     * Offset in the term buffer until which we've already outputted all alternatives. When this is
     * equal to `curTermLength`, we're finished with the token.
     */
    private int curPos;

    public OcrAlternativesFilter(TokenStream input) {
      super(input);
    }

    private OcrCharFilter getInputCharFilter(TokenStream input) {
      if (!(input instanceof Tokenizer)) {
        return null;
      }
      Tokenizer tok = (Tokenizer) input;
      try {
        return (OcrCharFilter) FieldUtils.readField(tok, "input", true);
      } catch (ClassCastException | ReflectiveOperationException e) {
        return null;
      }
    }

    @Override
    public void reset() throws IOException {
      super.reset();
      this.inputFilter = getInputCharFilter(input);
      if (inputFilter == null) {
        throw new RuntimeException(
            "An OcrAlternativesFilterFactory must immediately follow a TokenizerFactory that has a OcrCharFilterFactory "
                + "as its direct input. Check your schema!");
      }
    }

    @Override
    public final boolean incrementToken() throws IOException {
      // Initialize variable to hold the index of the next alternative in the term buffer
      int nextAlternativeIdx = -1;
      boolean partial = false;
      if (curTermBuffer == null) {
        while (true) {
          if (!this.input.incrementToken()) {
            return false;
          }
          // Check if the new token has alternatives and is complete
          int start = offsetAtt.startOffset();
          Optional<OcrCharFilter.TokenWithAlternatives> tokOpt =
              inputFilter.getTokenWithAlternatives(start);
          if (!tokOpt.isPresent()) {
            // No alternatives, nothing to do
            return true;
          }
          OcrCharFilter.TokenWithAlternatives tok = tokOpt.get();
          partial = (start - tok.defaultFormStart) > 0;
          if (start >= tok.defaultFormEnd) {
            // Part of ocr token with alternatives, but not part of the beginning
            // -> OCR token unit got split, ignore this token
            continue;
          }
          break;
        }
        nextAlternativeIdx =
            CharBufUtils.indexOf(termAtt.buffer(), 0, termAtt.length(), ALTERNATIVE_MARKER);

        // Record the state so we can access it during `incrementToken` for the alternatives
        this.state = this.captureState();

        // Set the initial token state
        this.curTermBuffer = this.termAtt.buffer().clone();
        this.curTermLength = this.termAtt.length();
        this.curPos = 0;
      }

      int newOffset = -1;
      boolean isInitialForm = (curPos == 0);
      if (!isInitialForm) {
        // Restore all attributes for the token so the alternative has the same attributes, except
        // for the characters
        this.restoreState(this.state);
        int closingIdx =
            CharBufUtils.indexOf(
                this.curTermBuffer, this.curPos, this.curTermLength, ALTERNATIVE_MARKER);
        if (closingIdx - curPos >= 0) {
          String offsetStr = new String(this.curTermBuffer, curPos, (closingIdx - curPos));
          newOffset = Integer.parseInt(offsetStr);
          curPos = closingIdx + ALTERNATIVE_MARKER.length;
          nextAlternativeIdx =
              CharBufUtils.indexOf(
                  termAtt.buffer(), curPos, this.curTermLength, ALTERNATIVE_MARKER);
        } else {
          log.warn(
              "Encountered incomplete token with alternatives, skipping it and all further alternatives,"
                  + "check the maximum token length of your tokenizer!");
          this.curTermBuffer = null;
          this.curTermLength = -1;
          this.curPos = -1;
          return this.incrementToken();
        }
      }
      // Change the term attribute to contain the current alternative
      int end = nextAlternativeIdx >= 0 ? nextAlternativeIdx : curTermLength;
      this.termAtt.copyBuffer(this.curTermBuffer, curPos, end - curPos);
      if (newOffset >= 0) {
        // Update the term offsets so they point at the alternative in the input
        this.offsetAtt.setOffset(newOffset, newOffset + (end - curPos));
      } else if (isInitialForm && nextAlternativeIdx > 0) {
        this.offsetAtt.setOffset(
            // Update the end offset of the initial term so it points at the end of the term and not
            // at the end
            // of the last alternative
            this.offsetAtt.startOffset(),
            this.offsetAtt.startOffset() + (nextAlternativeIdx - curPos));
      }

      if (!isInitialForm) {
        // Every alternative is at the same position as the original token
        this.posIncAtt.setPositionIncrement(0);
      }
      if (end == curTermLength || partial) {
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
