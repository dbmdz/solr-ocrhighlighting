package de.digitalcollections.solrocr.lucene.filters;

import java.io.Reader;
import java.util.HashMap;
import org.apache.lucene.analysis.CharFilter;
import org.apache.lucene.analysis.charfilter.HTMLStripCharFilter;
import org.apache.lucene.analysis.charfilter.MappingCharFilter;
import org.apache.lucene.analysis.charfilter.NormalizeCharMap;
import org.apache.lucene.analysis.charfilter.NormalizeCharMap.Builder;
import org.apache.lucene.analysis.util.CharFilterFactory;

/**
 * CharFilter to convert HTML/XML to plaintext while resolving hyphenation.
 *
 * This filter will:
 * <ul>
 *   <li>Strip all HTML tags, leaving only the plaintext</li>
 *   <li>Strip all soft hyphens to make Lucene consider hyphenated tokens as one</li>
 * </ul>
 */
public class DehyphenatingHtmlCharFilterFactory extends CharFilterFactory {
  private final NormalizeCharMap normMap;

  public DehyphenatingHtmlCharFilterFactory() {
    super(new HashMap<>());
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
