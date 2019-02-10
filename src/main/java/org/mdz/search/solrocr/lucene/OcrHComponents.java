package org.mdz.search.solrocr.lucene;

import java.util.Set;
import java.util.function.Predicate;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.uhighlight.PhraseHelper;
import org.apache.lucene.search.uhighlight.UHComponents;
import org.apache.lucene.search.uhighlight.UnifiedHighlighter.HighlightFlag;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.automaton.CharacterRunAutomaton;
import org.mdz.search.solrocr.lucene.byteoffset.ByteOffsetPhraseHelper;

/**
 * Components for the {@link OcrHighlighter}, with support for loading byte offsets from payloads.
 */
public class OcrHComponents extends UHComponents {
  private final ByteOffsetPhraseHelper byteOffsetPhraseHelper;

  public OcrHComponents(String field, Predicate<String> fieldMatcher,
      Query query, BytesRef[] terms,
      PhraseHelper phraseHelper,
      CharacterRunAutomaton[] automata,
      Set<HighlightFlag> highlightFlags) {
    super(field, fieldMatcher, query, terms, phraseHelper, automata, highlightFlags);
    this.byteOffsetPhraseHelper = null;
  }

  public OcrHComponents(
      String field, Predicate<String> fieldMatcher,
      Query query, BytesRef[] terms,
      PhraseHelper phraseHelper,
      ByteOffsetPhraseHelper byteOffsetPhraseHelper,
      CharacterRunAutomaton[] automata,
      Set<HighlightFlag> highlightFlags) {
    super(field, fieldMatcher, query, terms, phraseHelper, automata, highlightFlags);
    this.byteOffsetPhraseHelper = byteOffsetPhraseHelper;
  }

  public ByteOffsetPhraseHelper getByteOffsetPhraseHelper() {
    return byteOffsetPhraseHelper;
  }
}
