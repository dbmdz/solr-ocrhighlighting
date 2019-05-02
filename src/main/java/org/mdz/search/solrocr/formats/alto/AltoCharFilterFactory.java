package org.mdz.search.solrocr.formats.alto;

import java.io.Reader;
import java.util.Map;
import java.util.regex.Pattern;
import org.apache.lucene.analysis.CharFilter;
import org.apache.lucene.analysis.charfilter.HTMLStripCharFilter;
import org.apache.lucene.analysis.pattern.PatternReplaceCharFilter;
import org.apache.lucene.analysis.util.CharFilterFactory;

/**
 * CharFilter to convert ALTO to plaintext while keeping track of the token offsets.
 *
 * This filter will:
 * <ul>
 *   <li>Strip out the <code>&lt;Description&gt;...&lt;/Description&gt;</code> element</li>
 *   <li>Extract the OCRed text out of the <code>CONTENT</code> attribute into a text node so it's not removed by
 *   the {@link org.apache.lucene.analysis.charfilter.HTMLStripCharFilter}</li>
 *   <li>Strip out all XML-Tags, leaving only text nodes</li>
 * </ul>
 */
public class AltoCharFilterFactory extends CharFilterFactory {
  private static final Pattern DESC_PAT = Pattern.compile("<Description>.+</Description>", Pattern.DOTALL);
  private static final Pattern CONTENT_PAT = Pattern.compile("CONTENT=['\"](.+?)['\"]");
  private static final Pattern SUFFIX_PAT = Pattern.compile("<(\\s*)/>");

  public AltoCharFilterFactory(Map<String, String> args) {
    super(args);
  }

  @Override
  public Reader create(Reader input) {
    // The `<Description>` element contains text nodes that we don't want in the index, so we strip it
    CharFilter descFilter = new PatternReplaceCharFilter(DESC_PAT, "", input);
    // The OCR content for a given word is stored in the @CONTENT attribute, which would be stripped by the
    // HTMLStripCharFilter down the line, so we move it to a text node to keep it safe.
    // We need to keep the replacement the same length as the input, since the offsets inside of the span should not
    // move, so we pad with whitespace where necessary.
    CharFilter contentFilter = new PatternReplaceCharFilter(CONTENT_PAT, "        >$1<", descFilter);
    // If the `CONTENT` attribute comes last, the replaced output will have a `</>` suffix that will not
    // be removed by a subsequent HTMLStripCharFilter, so we strip these suffixes
    CharFilter suffixFilter = new PatternReplaceCharFilter(SUFFIX_PAT, " $1  ", contentFilter);
    // Strip out all XML tags, leaving only the OCR plain text
    CharFilter htmlFilter = new HTMLStripCharFilter(suffixFilter);
    return htmlFilter;
  }
}
