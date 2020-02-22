package de.digitalcollections.solrocr.lucene.filters;

import com.google.common.collect.ImmutableSet;
import de.digitalcollections.solrocr.model.OcrFormat;
import de.digitalcollections.solrocr.formats.alto.AltoFormat;
import de.digitalcollections.solrocr.formats.hocr.HocrFormat;
import de.digitalcollections.solrocr.formats.mini.MiniOcrFormat;
import de.digitalcollections.solrocr.reader.PeekingReader;
import java.io.Reader;
import java.util.Map;
import org.apache.lucene.analysis.util.CharFilterFactory;

/**
 * A CharFilterFactory that detects the OCR format from the input and creates the correct CharFilter instance
 * to convert the input OCR to plaintext.
 */
public class OcrCharFilterFactory extends CharFilterFactory {
  private static int BUF_SIZE = 2048;
  private static ImmutableSet<OcrFormat> FORMATS = ImmutableSet.of(
      new HocrFormat(),
      new AltoFormat(),
      new MiniOcrFormat());

  public OcrCharFilterFactory(Map<String, String> args) {
    // We don't take any args at the moment
    super(args);
  }

  @Override
  public Reader create(Reader input) {
    PeekingReader peeker = new PeekingReader(input, BUF_SIZE);
    OcrFormat fmt = FORMATS.stream()
        .filter(f -> f.hasFormat(peeker.peekBeginning()))
        .findFirst()
        .orElseThrow(() -> new RuntimeException(
            "Could not determine OCR format from chunk: " + peeker.peekBeginning()));
    Reader formatFilter = fmt.filter(peeker);
    if (formatFilter == null) {
      return peeker;
    } else {
      return formatFilter;
    }
  }
}
