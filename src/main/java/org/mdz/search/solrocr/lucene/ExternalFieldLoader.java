package org.mdz.search.solrocr.lucene;

import java.io.IOException;
import org.mdz.search.solrocr.util.IterableCharSequence;

public interface ExternalFieldLoader {
  boolean isExternalField(String fieldName);

  IterableCharSequence loadField(String docId, String fieldName) throws IOException;
}
