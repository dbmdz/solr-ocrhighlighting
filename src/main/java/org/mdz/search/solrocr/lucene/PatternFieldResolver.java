package org.mdz.search.solrocr.lucene;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.core.PluginInfo;
import org.apache.solr.util.plugin.PluginInfoInitialized;

public class PatternFieldResolver implements ExternalFieldResolver, PluginInfoInitialized {
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
  public Path resolve(String docId, String fieldName) {
    return Paths.get(fieldPatterns.get(fieldName).replaceAll("\\{docId}", docId));
  }
}
