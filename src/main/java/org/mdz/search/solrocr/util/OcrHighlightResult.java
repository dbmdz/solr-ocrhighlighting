package org.mdz.search.solrocr.util;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.common.util.SimpleOrderedMap;
import org.mdz.search.solrocr.formats.OcrSnippet;

public class OcrHighlightResult {
  private final Map<String, OcrSnippet[]> fieldSnippets;
  private final Map<String, Integer> snippetCounts;


  public OcrHighlightResult() {
    snippetCounts = new HashMap<>();
    fieldSnippets = new HashMap<>();
  }

  public void addSnippetsForField(String field, OcrSnippet[] ocrSnippets) {
    this.fieldSnippets.put(field, ocrSnippets);
  }

  public void addSnippetCountForField(String field, int i) {
    this.snippetCounts.put(field, i);
  }

  public OcrSnippet[] getFieldSnippets(String field) {
    return fieldSnippets.get(field);
  }

  public int getSnippetCount(String field) {
    return snippetCounts.get(field);
  }

  public NamedList toNamedList() {
    SimpleOrderedMap out = new SimpleOrderedMap();
    for (String fieldName : fieldSnippets.keySet()) {
      SimpleOrderedMap fieldOut = new SimpleOrderedMap();
      int snipCount = getSnippetCount(fieldName);
      OcrSnippet[] snips = getFieldSnippets(fieldName);
      NamedList[] outSnips = Arrays.stream(snips)
          .map(OcrSnippet::toNamedList)
          .toArray(NamedList[]::new);
      fieldOut.add("snippets", outSnips);
      fieldOut.add("numTotal", snipCount);
      out.add(fieldName, fieldOut);
    }
    return out;
  }
}
