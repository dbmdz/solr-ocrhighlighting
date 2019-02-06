package org.mdz.search.solrocr.formats;

import java.util.Arrays;
import org.apache.lucene.search.uhighlight.Passage;
import org.apache.lucene.search.uhighlight.PassageFormatter;
import org.mdz.search.solrocr.util.IterableCharSequence;

public abstract class OcrPassageFormatter extends PassageFormatter {
  public abstract OcrSnippet[] format(Passage[] passages, IterableCharSequence content);

  @Override
  public Object format(Passage[] passages, String content) {
    OcrSnippet[] snips = this.format(passages, IterableCharSequence.fromString(content));
    return Arrays.stream(snips).map(OcrSnippet::getText).toArray(String[]::new);
  }
}
