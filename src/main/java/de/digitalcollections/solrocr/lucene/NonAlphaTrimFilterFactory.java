package de.digitalcollections.solrocr.lucene;

import java.io.IOException;
import java.util.Map;
import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.util.TokenFilterFactory;

/**
 * This filter trims leading and/or/ trailing non-letter characters from tokens. This is useful if you're using
 * the whitespace tokenizer (e.g. because you need payloads), but want to have a similar post-processing as with
 * the standard tokenizer.
 */
public class NonAlphaTrimFilterFactory extends TokenFilterFactory {

  /**
   * Initialize this factory via a set of key-value pairs.
   */
  public NonAlphaTrimFilterFactory(Map<String, String> args) {
    super(args);
  }

  @Override
  public TokenStream create(TokenStream input) {
    return new NonAlphaTrimFilter(input);
  }

  public static final class NonAlphaTrimFilter extends TokenFilter {
    private final CharTermAttribute termAtt = addAttribute(CharTermAttribute.class);

    /**
     * Construct a token stream filtering the given input.
     */
    protected NonAlphaTrimFilter(TokenStream input) {
      super(input);
    }

    @Override
    public boolean incrementToken() throws IOException {
      if (!input.incrementToken()) return false;

      char[] termBuffer = termAtt.buffer();
      int len = termAtt.length();
      if (len == 0){
        return true;
      }
      int start = 0;
      int end = 0;

      // eat the first characters
      for (start = 0; start < len && shouldSkip(termBuffer, start); start++) {
      }

      // eat the end characters
      for (end = len; end >= start && shouldSkip(termBuffer, end - 1); end--) {
      }

      if (start > 0 || end < len) {
        if (start < end) {
          termAtt.copyBuffer(termBuffer, start, (end - start));
        } else {
          termAtt.setEmpty();
        }
      }

      return true;
    }

    private boolean shouldSkip(char[] termBuffer, int idx) {
      char c = termBuffer[idx];
      return !Character.isLetter(c) && !Character.isHighSurrogate(c) && !Character.isLowSurrogate(c);
    }
  }
}

