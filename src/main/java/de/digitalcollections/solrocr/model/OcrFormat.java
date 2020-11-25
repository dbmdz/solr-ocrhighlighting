package de.digitalcollections.solrocr.model;

import com.google.common.collect.Sets;
import de.digitalcollections.solrocr.formats.OcrParser;
import de.digitalcollections.solrocr.lucene.OcrPassageFormatter;
import de.digitalcollections.solrocr.lucene.filters.OcrCharFilter;
import de.digitalcollections.solrocr.reader.PeekingReader;
import java.io.Reader;
import java.text.BreakIterator;
import java.util.Set;
import org.apache.lucene.analysis.CharFilter;
import org.apache.lucene.search.uhighlight.PassageFormatter;

/**
 * Provides access to format-specific {@link BreakIterator} and {@link OcrPassageFormatter} instances.
 */
public interface OcrFormat {
  /** Get a {@link BreakIterator} that splits the content on a given block type.
   *
   * @param blockType the type of {@link OcrBlock} that the input document is split on
   * @return the {@link BreakIterator} instance
   * */
  BreakIterator getBreakIterator(OcrBlock blockType);

  /** Get the parser for the format.
   *
   * @param input the input reader to parse {@link OcrBox}es from
   * @param features Desired features for the parsers
   * @return a parser instance configured with the requested parsing features
   */
  OcrParser getParser(Reader input, OcrParser.ParsingFeature... features);

  /** Parse an {@link OcrPage} from a string fragment of the page markup.
   *
   * <p>Implementers are safe to assume that {@code pageFragment} begins with the opening tag of
   * a page, as determined by the format's {@link OcrFormat#getBreakIterator(OcrBlock)} output
   * for the {@link OcrBlock#PAGE} block type.
   *
   * @param pageFragment The beginning of a page's markup, i.e. a String starting with {@code <$pageElem}
   * @return the parsed {@link OcrPage}
   */
  OcrPage parsePageFragment(String pageFragment);

  /**
   * Get a {@link PassageFormatter} that builds OCR snippets from passages
   *
   * @param prehHighlightTag the tag to put in the snippet text before a highlighted region, e.g. &lt;em&gt;
   * @param postHighlightTag the tag to put in the snippet text after a highlighted region, e.g. &lt;/em&gt;
   * @param absoluteHighlights whether the coordinates for highlights should be absolute, i.e. relative to the page
   *                           and not the containing snippet
   * @param alignSpans whether the spans in the text and image should match precisely. If false, the text spans will
   *                   be more precise than the image "spans", since the latter are restricted to the granularity of
   *                   the OCR document.
   */
  default OcrPassageFormatter getPassageFormatter(
      String prehHighlightTag, String postHighlightTag,  boolean absoluteHighlights, boolean alignSpans) {
    return new OcrPassageFormatter(prehHighlightTag, postHighlightTag, absoluteHighlights, alignSpans, this);
  }

  /** Get a {@link CharFilter} implementation for the OCR format that outputs plaintext.
   *
   * If the filter supports outputting alternatives, it must output the alternatives
   *
   * @param input Input reader for OCR markup
   * @param expandAlternatives whether outputting alternatives from the OCR markup is desired.
   * @return a {@link CharFilter} implementation that outputs plaintext from the OCR.
   */
  default Reader filter(PeekingReader input, boolean expandAlternatives) {
    Set<OcrParser.ParsingFeature> features = Sets.newHashSet(
        OcrParser.ParsingFeature.TEXT, OcrParser.ParsingFeature.OFFSETS);
    if (expandAlternatives) {
      features.add(OcrParser.ParsingFeature.ALTERNATIVES);
    }
    return new OcrCharFilter(
        getParser(input, features.toArray(new OcrParser.ParsingFeature[]{})));
  }

  /**
   * Check if the string chunk contains data formatted according to the implementing format.
   *
   * @param ocrChunk a chunk of a file's content
   * @return whether the chunk is formatted according to the implementing format.
   */
  boolean hasFormat(String ocrChunk);

  int getLastContentStartIdx(String content);

  int getFirstContentEndIdx(String content);
}
