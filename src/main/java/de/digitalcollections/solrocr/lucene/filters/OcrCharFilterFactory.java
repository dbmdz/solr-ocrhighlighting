package de.digitalcollections.solrocr.lucene.filters;

import com.google.common.collect.ImmutableSet;
import de.digitalcollections.solrocr.formats.miniocr.MiniOcrFormat;
import de.digitalcollections.solrocr.model.OcrFormat;
import de.digitalcollections.solrocr.formats.alto.AltoFormat;
import de.digitalcollections.solrocr.formats.hocr.HocrFormat;
import de.digitalcollections.solrocr.reader.PeekingReader;
import java.io.Reader;
import java.util.Map;
import org.apache.lucene.analysis.util.CharFilterFactory;

/**
 * A CharFilterFactory that detects the OCR format from the input and creates the correct CharFilter instance
 * to convert the input OCR to plaintext.
 */
public class OcrCharFilterFactory extends CharFilterFactory {
  public static final String ALTERNATIVE_MARKER = "\u2060\u2060";

  private static final int BEGIN_BUF_SIZE = 2048;
  private static final int CTX_BUF_SIZE = 16384;

  private final boolean expandAlternatives;
  private final boolean fixMarkup;

  private static final ImmutableSet<OcrFormat> FORMATS = ImmutableSet.of(
      new HocrFormat(),
      new AltoFormat(),
      new MiniOcrFormat());

  public OcrCharFilterFactory(Map<String, String> args) {
    super(args);
    this.expandAlternatives = "true".equals(args.get("expandAlternatives"));
    this.fixMarkup = "true".equals(args.get("fixMarkup"));
  }

  @Override
  public Reader create(Reader input) {
    PeekingReader peeker = new PeekingReader(
       new SanitizingXmlFilter(input, fixMarkup), BEGIN_BUF_SIZE, CTX_BUF_SIZE);
    if (peeker.peekBeginning().isEmpty()) {
      // Empty document, no special treatment necessary
      return peeker;
    }
    OcrFormat fmt = FORMATS.stream()
        .filter(f -> f.hasFormat(peeker.peekBeginning()))
        .findFirst()
        .orElseThrow(() -> new RuntimeException(
            "Could not determine OCR format from chunk: " + peeker.peekBeginning()));
    Reader formatFilter = fmt.filter(peeker, expandAlternatives);
    if (formatFilter == null) {
      return peeker;
    } else {
      return formatFilter;
    }
  }
}
