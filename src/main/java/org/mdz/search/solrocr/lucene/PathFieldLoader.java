package org.mdz.search.solrocr.lucene;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.core.PluginInfo;
import org.apache.solr.util.plugin.PluginInfoInitialized;
import org.mdz.search.solrocr.util.FileCharIterator;
import org.mdz.search.solrocr.util.IterableCharSequence;

/** Load field values from filesystem paths.
 *
 * Must be configured with a mapping of field names to path patterns. In the pattern, <pre>{docId}</pre> is replaced
 * with the <pre>"id"</pre> field of the document that the field content is to be retrieved for.
 *
 * The files the resolver loads from <strong>must be encoded in UTF-16, all other encodings are not supported.</strong>
 * It is recommended to include a Byte-Order marker in the files, although the loader contains some heuristics to
 * detect the endianness without its presence.
 *
 * Example:
 *
 * <pre>
 * {@code
 *  <fieldLoader class="org.mdz.search.solrocr.lucene.PathFieldLoader">
 *    <lst name="externalFields">
 *      <str name="ocr_text">src/test/resources/data/{docId}_ocr.xml</str>
 *    </lst>
 *  </fieldLoader>
 *  }
 * </pre>
 *
 * */
public class PathFieldLoader implements ExternalFieldLoader, PluginInfoInitialized {
  private Map<String, String> fieldPatterns;

  @Override
  public void init(PluginInfo info) {
    this.fieldPatterns = new HashMap<>();
    NamedList<String> args = (NamedList<String>) info.initArgs.get("externalFields");
    args.forEach((fieldName, pattern) -> {
      fieldPatterns.put(fieldName, pattern);
    });
  }

  @Override
  public boolean isExternalField(String fieldName) {
    return fieldPatterns.containsKey(fieldName);
  }

  @Override
  public IterableCharSequence loadField(String docId, String fieldName) throws IOException {
    try {
      return new FileCharIterator(Paths.get(fieldPatterns.get(fieldName).replaceAll("\\{docId}", docId)));
    } catch (NoSuchFileException e) {
      return null;
    }
  }
}
