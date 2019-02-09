package org.mdz.search.solrocr.lucene;

import java.util.Map;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.payloads.DelimitedPayloadTokenFilter;
import org.apache.lucene.analysis.payloads.PayloadEncoder;
import org.apache.lucene.analysis.util.TokenFilterFactory;

import static org.apache.lucene.analysis.payloads.DelimitedPayloadTokenFilterFactory.DELIMITER_ATTR;

public class ByteOffsetPayloadTokenFilterFactory extends TokenFilterFactory {
  private final char delimiter;
  private final PayloadEncoder encoder;

  /**
   * Initialize this factory via a set of key-value pairs.
   */
  protected ByteOffsetPayloadTokenFilterFactory(Map<String, String> args) {
    super(args);
    delimiter = getChar(args, DELIMITER_ATTR, '⚑');
    encoder = new ByteOffsetEncoder();
  }

  @Override
  public TokenStream create(TokenStream input) {
    return new DelimitedPayloadTokenFilter(input, delimiter, encoder);
  }
}
