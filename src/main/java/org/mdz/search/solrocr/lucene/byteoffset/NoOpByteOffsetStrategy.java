package org.mdz.search.solrocr.lucene.byteoffset;

import java.io.IOException;
import java.util.Collections;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.search.MatchNoDocsQuery;
import org.apache.lucene.search.uhighlight.PhraseHelper;
import org.apache.lucene.search.uhighlight.UnifiedHighlighter.OffsetSource;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.automaton.CharacterRunAutomaton;
import org.mdz.search.solrocr.lucene.OcrHComponents;
import org.mdz.search.solrocr.lucene.OcrHighlighter;

/**
 * A variant of {@link org.apache.lucene.search.uhighlight.NoOpOffsetStrategy} for byte offsets from payloads
 */
public class NoOpByteOffsetStrategy extends FieldByteOffsetStrategy {
  public static final NoOpByteOffsetStrategy INSTANCE = new NoOpByteOffsetStrategy();

  private NoOpByteOffsetStrategy() {
    super(new OcrHComponents(
        "_ignored_",
        (s) -> false,
        new MatchNoDocsQuery(),
        new BytesRef[0],
        PhraseHelper.NONE,
        new CharacterRunAutomaton[0],
        Collections.emptySet()));
  }

  @Override
  public OffsetSource getOffsetSource() {
    return OcrHighlighter.OffsetSource.NONE_NEEDED;
  }

  @Override
  public ByteOffsetsEnum getByteOffsetsEnum(LeafReader reader, int docId) throws IOException {
    return ByteOffsetsEnum.EMPTY;
  }
}
