package de.digitalcollections.solrocr.formats.hocr;

import java.io.Reader;
import java.util.Map;
import org.apache.lucene.analysis.CharFilter;
import org.apache.lucene.analysis.charfilter.HTMLStripCharFilter;
import org.apache.lucene.analysis.charfilter.MappingCharFilter;
import org.apache.lucene.analysis.charfilter.NormalizeCharMap;
import org.apache.lucene.analysis.charfilter.NormalizeCharMap.Builder;
import org.apache.lucene.analysis.util.CharFilterFactory;

/**
 * CharFilter to convert hOCR to plaintext while resolving hyphenation.
 *
 * This filter will:
 * <ul>
 *   <li>Strip all HTML tags, leaving only the plaintext</li>
 *   <li>Strip all soft hyphens to make Lucene consider hyphenated tokens as one</li>
 * </ul>
 */
public class HocrCharFilterFactory extends CharFilterFactory {
  private final NormalizeCharMap normMap;

  public HocrCharFilterFactory(Map<String, String> args) {
    super(args);
    Builder builder = new Builder();
    builder.add("\u00AD", "");
    this.normMap = builder.build();
  }

  @Override
  public Reader create(Reader reader) {
    CharFilter htmlFilter = new HTMLStripCharFilter(reader);
    return new MappingCharFilter(normMap, htmlFilter);
  }
}
