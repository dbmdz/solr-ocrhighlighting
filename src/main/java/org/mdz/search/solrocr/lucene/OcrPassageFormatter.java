package org.mdz.search.solrocr.lucene;

import org.apache.lucene.search.uhighlight.Passage;
import org.apache.lucene.search.uhighlight.PassageFormatter;
import org.mdz.search.solrocr.util.FileCharIterable;

public class OcrPassageFormatter  extends PassageFormatter {
  public OcrSnippet format(Passage passages[], FileCharIterable content) {
    // TODO: Implement creating of OcrSnippet objects from passages and content
    return null;
  }

  @Override
  public Object format(Passage[] passages, String content) {
    throw new UnsupportedOperationException();
  }
}
