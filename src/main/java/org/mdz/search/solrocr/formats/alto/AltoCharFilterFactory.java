package org.mdz.search.solrocr.formats.alto;

import java.io.Reader;
import java.util.Map;
import java.util.regex.Pattern;
import org.apache.lucene.analysis.CharFilter;
import org.apache.lucene.analysis.pattern.PatternReplaceCharFilter;
import org.apache.lucene.analysis.util.AbstractAnalysisFactory;
import org.apache.lucene.analysis.util.CharFilterFactory;

/**
 * CharFilter to prepare ALTO input for consumption by a
 * {@link org.apache.lucene.analysis.charfilter.HTMLStripCharFilter}.
 *
 * This filter will:
 * <ul>
 *   <li>Strip out the <code>&lt;Description&gt;...&lt;/Description&gt;</code> element</li>
 *   <li>Extract the OCRed text out of the <code>CONTENT</code> attribute into a text node so it's not removed by
 *   the {@link org.apache.lucene.analysis.charfilter.HTMLStripCharFilter}</li>
 * </ul>
 */
public class AltoCharFilterFactory extends CharFilterFactory {
  private static final Pattern DESC_PAT = Pattern.compile("<Description>.+</Description", Pattern.DOTALL);
  private static final Pattern CONTENT_PAT = Pattern.compile("CONTENT=['\"](.+?)['\"]");

  public AltoCharFilterFactory(Map<String, String> args) {
    super(args);
  }

  @Override
  public Reader create(Reader input) {
    CharFilter descFilter = new PatternReplaceCharFilter(DESC_PAT, "", input);
    return new PatternReplaceCharFilter(CONTENT_PAT, "        >$1<", descFilter);
  }
}
