package de.digitalcollections.solrocr.formats;

import com.ctc.wstx.api.WstxInputProperties;
import com.ctc.wstx.stax.WstxInputFactory;
import com.google.common.collect.ImmutableMap;
import de.digitalcollections.solrocr.model.OcrBox;
import de.digitalcollections.solrocr.reader.PeekingReader;
import java.io.Reader;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import javax.xml.stream.XMLStreamException;
import org.codehaus.stax2.XMLStreamReader2;

/** Base class for OCR  parsers operating on XML markup */
public abstract class OcrParser implements Iterator<OcrBox>, Iterable<OcrBox> {

  /** Set of features that can be turned on/off depending on the downstream needs */
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

  // Named XML character entities that are used in hOCR
  public static final ImmutableMap<Object, Object> ENTITIES = ImmutableMap.builder()
      .put("shy", "\u00ad")
      .put("nbsp", "\u00a0")
      .put("ensp", "\u2002")
      .put("emsp", "\u2003")
      .put("thinsp", "\u2009")
      .put("zwnj", "\u200c")
      .put("zwj", "\u200d")
      .build();
  public static final String START_HL = "\uD83D\uDD25"; //🔥
  public static final String END_HL = "\uD83E\uDDEF"; //🧯

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

    // Woodstax sometimes splits long text nodes, this option forces it to merge them together
    // before passing them to us
    xmlInputFactory.getConfig().doCoalesceText(true);
    // This parsing mode allows us to read multiple "concatenated" XML documents in a single pass
    xmlInputFactory.getConfig()
        .setInputParsingMode(WstxInputProperties.PARSING_MODE_DOCUMENTS);
    // Ignore DTDs since they cause lookups to external URLs
    xmlInputFactory.getConfig().doSupportDTDs(false);
    // Register custom named entities used by hOCR
    xmlInputFactory.getConfig().setCustomInternalEntities(ENTITIES);
    this.xmlReader = (XMLStreamReader2) xmlInputFactory.createXMLStreamReader(this.input);
    try {
      this.nextWord = this.readNext(this.xmlReader, this.features);
    } catch (XMLStreamException e) {
      throw new RuntimeException(String.format(
          "Failed to parse the OCR markup, make sure your files are well-formed and your regions start/end on " +
          "complete tags! (Source was: %s)", this.input.getSource().orElse("[unknown]")), e);
    }
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
      do {
        this.nextWord = readNext(xmlReader, features);
      } while (hasNext() && this.nextWord == null);
    } catch (XMLStreamException e) {
      throw new RuntimeException(String.format(
          "Failed to parse the OCR markup, make sure your files are well-formed and your regions start/end on " +
          "complete tags! (Source was: %s)", this.input.getSource().orElse("[unknown]")), e);
    }
    return out;
  }

  /** "Peek" at the next word from the parse without advancing the parse to the word after it
   *  (i.e. calling this does not influence the result of the `next()` call **/
  public Optional<OcrBox> peek() {
    if (!hasNext()) {
      return Optional.empty();
    }
    return Optional.of(this.nextWord);
  }

  /** Keep track of highlighted box spans encountered during parsing.
   *
   * Implements should always call this method when they encounter OCR text, since it might
   * contain highlighting markers that we need to track.
   *
   * Returns the identifier of the box's highlighting span, if present, else null.
   */
  protected UUID trackHighlightSpan(String text, OcrBox box) {
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

  /** Get the underlying peeking input reader. */
  public PeekingReader getInput() {
    return input;
  }

  /** Read the next OCR box in the input stream.
   *
   * Implementers should take care to enable/disable various parsing steps depending on the set of
   * features passed in.
   */
  protected abstract OcrBox readNext(XMLStreamReader2 xmlReader, Set<ParsingFeature> features)
      throws XMLStreamException;


  /** Helper method to convert a list of OCR boxes to a text string.
   *
   * Includes smart handling of partial hyphenations as well as handling of alternative tokens
   * that are at the end and/or beginning of a highlighted span. In these cases the highlighted
   * alternative will be used in the output string instead of the default form of the box. This
   * is only possible if the alternative is at the beginning or end, since we otherwise don't have
   * any information available to us if the default form or an alternative matched.
   */
  public static String boxesToString(List<OcrBox> boxes) {
    StringBuilder sb = new StringBuilder();
    int idx = 0;
    Iterator<OcrBox> it = boxes.iterator();
    while (it.hasNext()) {
      OcrBox b = it.next();
      if (b.isHyphenated() && b.isHyphenStart()) {
        boolean wordIsCompleteHyphenation = (
            idx < boxes.size() - 1
            && boxes.get(idx + 1).isHyphenated() && !boxes.get(idx + 1).isHyphenStart());
        if (wordIsCompleteHyphenation) {
          // Both parts of the hyphenation are present, put the dehyphenated form in the text
          OcrBox next = it.next();
          sb.append(next.getDehyphenatedForm());
          b.setTrailingChars(next.getTrailingChars());
          idx += 1;
        } else {
          // An isolated hyphen start without its corresponding ending, denote the hyphenation
          // explicitly
          String text = b.getText().trim();
          if (!text.endsWith("-")) {
            text += "-";
          }
          sb.append(text);
        }
      } else if (!b.getAlternatives().isEmpty()) {
        Optional<String> alternativeWithHighlight = b.getAlternatives().stream()
            .filter(a -> a.contains(START_HL) || a.contains(END_HL))
            .findFirst();
        // If the highlight is on an alternative, output that alternative instead of the default token
        if (alternativeWithHighlight.isPresent()) {
          sb.append(alternativeWithHighlight.get());
        } else {
          sb.append(b.getText());
        }
      } else {
        sb.append(b.getText());
      }
      if (b.getTrailingChars() != null) {
        sb.append(b.getTrailingChars());
      }
      idx += 1;
    }
    return sb.toString().trim();
  }
}
