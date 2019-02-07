package org.mdz.search.solrocr.lucene;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.core.PluginInfo;
import org.apache.solr.util.plugin.PluginInfoInitialized;
import org.mdz.search.solrocr.util.FileCharIterator;
import org.mdz.search.solrocr.util.IterableCharSequence;

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
    return new FileCharIterator(Paths.get(fieldPatterns.get(fieldName).replaceAll("\\{docId}", docId)));
  }
}
