package de.digitalcollections.solrocr.lucene.byteoffset;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.search.highlight.TermVectorLeafReader;
import org.apache.lucene.search.uhighlight.OverlaySingleDocTermsLeafReader;
import org.apache.lucene.search.uhighlight.UnifiedHighlighter.OffsetSource;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.CharsRefBuilder;
import org.apache.lucene.util.automaton.CharacterRunAutomaton;
import de.digitalcollections.solrocr.lucene.OcrHComponents;
import de.digitalcollections.solrocr.lucene.vendor.TermVectorFilteredLeafReader;

/**
 * Customization of {@link org.apache.lucene.search.uhighlight.FieldOffsetStrategy} to load byte offsets from payloads.
 *
 * A lot of this code is copied straight from the above class.
 */
public abstract class FieldByteOffsetStrategy {
  protected final OcrHComponents components;

  public FieldByteOffsetStrategy(OcrHComponents components) {
    this.components = components;
  }

  public String getField() {
    return components.getField();
  }

  public abstract OffsetSource getOffsetSource();

  public abstract ByteOffsetsEnum getByteOffsetsEnum(LeafReader leafReader, int doc) throws IOException;

  protected ByteOffsetsEnum createByteOffsetsEnumFromReader(LeafReader leafReader, int doc) throws IOException {
    final Terms termsIndex = leafReader.terms(getField());
    if (termsIndex == null) {
      return ByteOffsetsEnum.EMPTY;
    }

    final List<ByteOffsetsEnum> byteOffsetsEnums = new ArrayList<>();

    // Handle position insensitive terms (a subset of this.terms field):
    final BytesRef[] insensitiveTerms;
    final ByteOffsetPhraseHelper phraseHelper = components.getByteOffsetPhraseHelper();
    final BytesRef[] terms = components.getTerms();
    if (phraseHelper.hasPositionSensitivity()) {
      insensitiveTerms = phraseHelper.getAllPositionInsensitiveTerms();
      assert insensitiveTerms.length <= terms.length : "insensitive terms should be smaller set of all terms";
    } else {
      insensitiveTerms = terms;
    }
    if (insensitiveTerms.length > 0) {
      createByteOffsetsEnumsForTerms(insensitiveTerms, termsIndex, doc, byteOffsetsEnums);
    }

    // Handle spans
    if (phraseHelper.hasPositionSensitivity()) {
      phraseHelper.createByteOffsetsEnumsForSpans(leafReader, doc, byteOffsetsEnums);
    }

    // Handle automata
    if (components.getAutomata().length > 0) {
      createByteOffsetsEnumsForAutomata(termsIndex, doc, byteOffsetsEnums);
    }

    switch (byteOffsetsEnums.size()) {
      case 0: return ByteOffsetsEnum.EMPTY;
      case 1: return byteOffsetsEnums.get(0);
      default: return new ByteOffsetsEnum.MultiByteOffsetsEnum(byteOffsetsEnums);
    }
  }

  protected void createByteOffsetsEnumsForTerms(
      BytesRef[] sourceTerms, Terms termsIndex, int doc, List<ByteOffsetsEnum> results) throws IOException {
    TermsEnum termsEnum = termsIndex.iterator();//does not return null
    for (BytesRef term : sourceTerms) {
      if (termsEnum.seekExact(term)) {
        PostingsEnum postingsEnum = termsEnum.postings(null, PostingsEnum.PAYLOADS);
        if (postingsEnum == null) {
          // no offsets or positions available
          throw new IllegalArgumentException(
              "field '" + getField() + "' was indexed without payloads, cannot highlight");
        }
        if (doc == postingsEnum.advance(doc)) { // now it's positioned, although may be exhausted
          results.add(new ByteOffsetsEnum.OfPostings(term, postingsEnum));
        }
      }
    }
  }

  protected void createByteOffsetsEnumsForAutomata(
      Terms termsIndex, int doc, List<ByteOffsetsEnum> results) throws IOException {
    final CharacterRunAutomaton[] automata = components.getAutomata();
    List<List<PostingsEnum>> automataPostings = new ArrayList<>(automata.length);
    for (int i = 0; i < automata.length; i++) {
      automataPostings.add(new ArrayList<>());
    }

    TermsEnum termsEnum = termsIndex.iterator();
    BytesRef term;

    CharsRefBuilder refBuilder = new CharsRefBuilder();
    while ((term = termsEnum.next()) != null) {
      for (int i = 0; i < automata.length; i++) {
        CharacterRunAutomaton automaton = automata[i];
        refBuilder.copyUTF8Bytes(term);
        if (automaton.run(refBuilder.chars(), 0, refBuilder.length())) {
          PostingsEnum postings = termsEnum.postings(null, PostingsEnum.PAYLOADS);
          if (doc == postings.advance(doc)) {
            automataPostings.get(i).add(postings);
          }
        }
      }
    }

    for (int i = 0; i < automata.length; i++) {
      CharacterRunAutomaton automaton = automata[i];
      List<PostingsEnum> postingsEnums = automataPostings.get(i);
      if (postingsEnums.isEmpty()) {
        continue;
      }
      // Build one OffsetsEnum exposing the automata.toString as the term, and the sum of freq
      BytesRef wildcardTerm = new BytesRef(automaton.toString());
      int sumFreq = 0;
      for (PostingsEnum postingsEnum : postingsEnums) {
        sumFreq += postingsEnum.freq();
      }
      for (PostingsEnum postingsEnum : postingsEnums) {
        results.add(new ByteOffsetsEnum.OfPostings(wildcardTerm, sumFreq, postingsEnum));
      }
    }
  }

  public static class TermVectorByteOffsetStrategy extends FieldByteOffsetStrategy {
    public TermVectorByteOffsetStrategy(OcrHComponents components) {
      super(components);
    }

    @Override
    public OffsetSource getOffsetSource() {
      return OffsetSource.TERM_VECTORS;
    }

    @Override
    public ByteOffsetsEnum getByteOffsetsEnum(LeafReader reader, int docId) throws IOException {
      Terms tvTerms = reader.getTermVector(docId, getField());
      if (tvTerms == null) {
        return ByteOffsetsEnum.EMPTY;
      }

      LeafReader singleDocReader = new TermVectorLeafReader(getField(), tvTerms);
      return createByteOffsetsEnumFromReader(
          new OverlaySingleDocTermsLeafReader(
              reader,
              singleDocReader,
              getField(),
              docId),
          docId);
    }
  }

  public static class PostingsByteOffsetStrategy extends FieldByteOffsetStrategy {
    public PostingsByteOffsetStrategy(OcrHComponents components) {
      super(components);
    }

    @Override
    public OffsetSource getOffsetSource() {
      return OffsetSource.POSTINGS;
    }

    @Override
    public ByteOffsetsEnum getByteOffsetsEnum(LeafReader leafReader, int doc) throws IOException {
      return createByteOffsetsEnumFromReader(leafReader, doc);
    }
  }

  public static class PostingsWithTermVectorsByteOffsetStrategy extends FieldByteOffsetStrategy {

    public PostingsWithTermVectorsByteOffsetStrategy(OcrHComponents components) {
      super(components);
    }

    @Override
    public OffsetSource getOffsetSource() {
      return OffsetSource.POSTINGS_WITH_TERM_VECTORS;
    }

    @Override
    public ByteOffsetsEnum getByteOffsetsEnum(LeafReader leafReader, int doc) throws IOException {
      Terms docTerms = leafReader.getTermVector(doc, getField());
      if (docTerms == null) {
        return ByteOffsetsEnum.EMPTY;
      }
      leafReader = new TermVectorFilteredLeafReader(leafReader, docTerms, getField());

      return createByteOffsetsEnumFromReader(leafReader, doc);
    }
  }

}
