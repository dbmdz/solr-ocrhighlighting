package org.mdz.search.solrocr.lucene.fieldloader;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.SolrException.ErrorCode;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.core.PluginInfo;
import org.apache.solr.util.plugin.PluginInfoInitialized;
import org.mdz.search.solrocr.util.FileBytesCharIterator;
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
 *  <fieldLoader class="org.mdz.search.solrocr.lucene.fieldloader.PathFieldLoader">
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
  private Charset charset;

  @Override
  public void init(PluginInfo info) {
    String cset = info.attributes.get("encoding");
    if (cset == null) {
      cset = "utf-16";
    }
    this.charset = Charset.forName(cset);
    if (this.charset != StandardCharsets.UTF_8 && this.charset != StandardCharsets.UTF_16
        && this.charset != StandardCharsets.UTF_16LE && this.charset != StandardCharsets.UTF_16BE) {
      throw new SolrException(
          ErrorCode.FORBIDDEN,
          String.format("Invalid encoding '%s', must be one of 'utf-8', 'utf-16', 'utf-16le' or 'utf-16be'", cset));
    }
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
    Path p = Paths.get(fieldPatterns.get(fieldName).replaceAll("\\{docId}", docId));
    try {
      if (this.charset == StandardCharsets.UTF_8) {
        return new FileBytesCharIterator(p);
      } else {
        return new FileCharIterator(p);
      }
    } catch (NoSuchFileException e) {
      return null;
    }
  }
}
