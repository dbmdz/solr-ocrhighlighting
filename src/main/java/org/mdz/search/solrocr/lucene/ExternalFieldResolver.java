package org.mdz.search.solrocr.lucene;

import java.nio.file.Path;

public interface ExternalFieldResolver {
  boolean isExternalField(String fieldName);

  Path resolve(String docId, String fieldName);
}
