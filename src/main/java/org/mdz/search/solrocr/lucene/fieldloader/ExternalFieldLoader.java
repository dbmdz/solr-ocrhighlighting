package org.mdz.search.solrocr.lucene.fieldloader;

import java.io.IOException;
import org.mdz.search.solrocr.util.IterableCharSequence;

/** Allows loading field values from arbitrary sources outside of Solr/Lucene */
public interface ExternalFieldLoader {

  /** Check if the field content is located in an external source */
  boolean isExternalField(String fieldName);

  /** Load the field content from an external source */
  IterableCharSequence loadField(String docId, String fieldName) throws IOException;
}
