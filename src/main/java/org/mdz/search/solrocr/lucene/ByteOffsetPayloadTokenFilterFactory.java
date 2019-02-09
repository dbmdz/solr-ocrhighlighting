package org.mdz.search.solrocr.lucene;

import java.util.Map;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.util.TokenFilterFactory;

import static org.apache.lucene.analysis.payloads.DelimitedPayloadTokenFilterFactory.DELIMITER_ATTR;

public class ByteOffsetPayloadTokenFilterFactory extends TokenFilterFactory {
  private final char delimiter;

  /**
   * Initialize this factory via a set of key-value pairs.
   */
  protected ByteOffsetPayloadTokenFilterFactory(Map<String, String> args) {
    super(args);

    delimiter = getChar(args, DELIMITER_ATTR, '|');
  }

  @Override
  public TokenStream create(TokenStream input) {
    return null;
  }
}
