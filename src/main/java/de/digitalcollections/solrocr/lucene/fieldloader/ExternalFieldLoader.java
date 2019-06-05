package de.digitalcollections.solrocr.lucene.fieldloader;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Map;
import java.util.Set;
import de.digitalcollections.solrocr.util.IterableCharSequence;

/** Allows loading field values from arbitrary sources outside of Solr/Lucene */
public interface ExternalFieldLoader {

  /** Check if the field content is located in an external source */
  boolean isExternalField(String fieldName);

  /** Get the names of the fields that are required for {@link #loadField(Map, String)} */
  Set<String> getRequiredFields();

  /** Load the field content from an external source */
  IterableCharSequence loadField(Map<String, String> fields, String fieldName) throws IOException;

  /** Get the charset that field values will be encoded in */
  Charset getCharset();
}
