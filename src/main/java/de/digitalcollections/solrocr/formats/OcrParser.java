package de.digitalcollections.solrocr.formats;

import com.ctc.wstx.api.WstxInputProperties;
import com.ctc.wstx.stax.WstxInputFactory;
import de.digitalcollections.solrocr.model.OcrBox;
import de.digitalcollections.solrocr.reader.PeekingReader;
import java.io.Reader;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import javax.xml.stream.XMLStreamException;
import org.codehaus.stax2.XMLStreamReader2;

public abstract class OcrParser implements Iterator<OcrBox>, Iterable<OcrBox> {

  public enum ParsingFeature {
    /** Parse text, i.e. "default", alternatives and hyphenated forms */
    TEXT,
    /** Parse offsets for texts */
    OFFSETS,
    /** Parse coordinates */
    COORDINATES,
    /** Track highlight spans during parsing */
    HIGHLIGHTS,
    /** Parse confidence (0..1) if available */
    CONFIDENCE,
    /** Parse alternatives */
    ALTERNATIVES,
    /** Track page changes */
    PAGES,
  }

  public static final String START_HL = "\uD83D\uDD25";
  public static final String END_HL = "\uD83E\uDDEF";

  private static final WstxInputFactory xmlInputFactory = new WstxInputFactory();

  protected final PeekingReader input;
  protected UUID currentHighlightSpan;
  protected boolean terminateHighlightSpanAfterNext = false;

  private final XMLStreamReader2 xmlReader;
  private final Set<ParsingFeature> features = new HashSet<>();

  private OcrBox nextWord;

  public OcrParser(Reader input, ParsingFeature... features) throws XMLStreamException {
    if (input instanceof PeekingReader) {
      this.input = (PeekingReader) input;
    } else {
      this.input = new PeekingReader(input, 2048, 16384);
    }
    if (features.length == 0) {
      features = new ParsingFeature[]{
          ParsingFeature.TEXT, ParsingFeature.OFFSETS, ParsingFeature.COORDINATES, ParsingFeature.HIGHLIGHTS,
          ParsingFeature.CONFIDENCE, ParsingFeature.ALTERNATIVES, ParsingFeature.PAGES};
    }
    this.features.addAll(Arrays.asList(features));
    xmlInputFactory.getConfig()
        .setInputParsingMode(WstxInputProperties.PARSING_MODE_DOCUMENTS);
    xmlInputFactory.getConfig()
        .doSupportDTDs(false);
    this.xmlReader = (XMLStreamReader2) xmlInputFactory.createXMLStreamReader(this.input);
    this.nextWord = this.readNext(this.xmlReader, this.features);
  }

  @Override
  public Iterator<OcrBox> iterator() {
    return this;
  }

  public Stream<OcrBox> stream() {
    return StreamSupport.stream(Spliterators.spliteratorUnknownSize(this, Spliterator.ORDERED), false);
  }

  @Override
  public boolean hasNext() {
    return this.nextWord != null;
  }

  @Override
  public OcrBox next() {
    if (!hasNext()) {
      throw new IllegalStateException("No more words in input");
    }
    OcrBox out = this.nextWord;
    try {
      this.nextWord = readNext(xmlReader, features);
    } catch (XMLStreamException e) {
      throw new RuntimeException(
          "Failed to parse the OCR markup, make sure your files are well-formed and your regions start/end on " +
          "complete tags!",  e);
    }
    return out;
  }


  protected UUID trackHighlightSpan(String text, OcrBox box) {
    // TODO: Dehyphenation is tricky:
    //  - We track the offset of the hyphen's first part
    //  - As a consequence, only the first part will include the hyphen end
    //  - But we want the second part to remain in the highlight span as well and only finish it after it has passed
    if (this.currentHighlightSpan == null && text.contains(OcrParser.START_HL)) {
      this.currentHighlightSpan = UUID.randomUUID();
    }
    if ( this.currentHighlightSpan != null && (terminateHighlightSpanAfterNext || text.contains(OcrParser.END_HL))) {
      // Highlight spans that end on the start of a hyphenation should stretch as far as the end of the hyphenation,
      // so we toggle a flag to delay the termination
      if (terminateHighlightSpanAfterNext) {
        terminateHighlightSpanAfterNext = false;
      } else if (box.isHyphenated() && box.isHyphenStart() && text.contains(OcrParser.END_HL)) {
        terminateHighlightSpanAfterNext = true;
        return this.currentHighlightSpan;
      }
      UUID out = this.currentHighlightSpan;
      this.currentHighlightSpan = null;
      return out;
    }
    return this.currentHighlightSpan;
  }

  public PeekingReader getInput() {
    return input;
  }

  protected abstract OcrBox readNext(XMLStreamReader2 xmlReader, Set<ParsingFeature> features)
      throws XMLStreamException;


  public static String boxesToString(Iterable<OcrBox> boxes) {
    StringBuilder sb = new StringBuilder();
    boxes.forEach(b -> {
      if (b.isHyphenated()) {
        if (b.isHyphenStart()) {
          sb.append(b.getDehyphenatedForm());
        }
      } else {
        sb.append(b.getText());
      }
      if (b.getTrailingChars() != null) {
        sb.append(b.getTrailingChars());
      }
    });
    return sb.toString().trim();
  }
}
