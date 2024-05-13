/*
 * Contains verbatim code and custom code based on code from the Solr
 * project, licensed under the following terms. All parts where this is
 * the case are clearly marked as such in a source code comment referring
 * to this header.
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE.upstream file distributed
 * with this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * For all parts where this is not the case, refer to the LICENSE file in the
 * repository root.
 */
package com.github.dbmdz.solrocr.solr;

import com.github.dbmdz.solrocr.model.OcrBlock;
import com.github.dbmdz.solrocr.model.OcrHighlightResult;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.apache.lucene.search.Query;
import org.apache.solr.common.params.HighlightParams;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.common.util.SimpleOrderedMap;
import org.apache.solr.highlight.UnifiedSolrHighlighter;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.search.DocList;
import org.apache.solr.util.SolrPluginUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import solrocr.OcrHighlighter;

public class SolrOcrHighlighter extends UnifiedSolrHighlighter {
  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  private final Executor hlThreadPool;
  private final Map<String, Map<OcrBlock, Integer>> formatReadSizes;

  public SolrOcrHighlighter() {
    this(Runtime.getRuntime().availableProcessors(), 8, null);
  }

  public SolrOcrHighlighter(
      int numHlThreads,
      int maxQueuedPerThread,
      Map<String, Map<OcrBlock, Integer>> formatReadSizes) {
    super();
    if (numHlThreads > 0) {
      this.hlThreadPool =
          new ThreadPoolExecutor(
              numHlThreads,
              numHlThreads,
              120L,
              TimeUnit.SECONDS,
              new LinkedBlockingQueue<>(numHlThreads * maxQueuedPerThread),
              new ThreadFactoryBuilder().setNameFormat("OcrHighlighter-%d").build());
    } else {
      // Executors.newDirectExecutorService() for Java 8
      this.hlThreadPool =
          new Executor() {
            @Override
            public void execute(Runnable cmd) {
              cmd.run();
            }
          };
    }
    this.formatReadSizes = formatReadSizes;
  }

  public void shutdownThreadPool() {
    if (hlThreadPool instanceof ThreadPoolExecutor) {
      ((ThreadPoolExecutor) hlThreadPool).shutdown();
    }
  }

  public NamedList<Object> doHighlighting(
      DocList docs, Query query, SolrQueryRequest req, Map<String, Object> respHeader)
      throws IOException {
    // Copied from superclass
    // - *snip* -
    final SolrParams params = req.getParams();
    if (!isHighlightingEnabled(params)) {
      return null;
    }
    if (docs.size() == 0) {
      return new SimpleOrderedMap<>();
    }
    int[] docIDs = toDocIDs(docs);
    String[] keys = getUniqueKeys(req.getSearcher(), docIDs);
    // - *snap* -

    // query-time parameters
    String[] ocrFieldNames = getOcrHighlightFields(req);
    // No output if no fields were defined
    if (ocrFieldNames == null || ocrFieldNames.length == 0) {
      return null;
    }
    int[] maxPassagesOcr = getMaxPassages(ocrFieldNames, params);

    // Highlight OCR fields
    OcrHighlighter ocrHighlighter =
        new OcrHighlighter(
            req.getSearcher(), req.getSchema().getIndexAnalyzer(), req, formatReadSizes);
    OcrHighlightResult[] ocrSnippets =
        ocrHighlighter.highlightOcrFields(
            ocrFieldNames, query, docIDs, maxPassagesOcr, respHeader, hlThreadPool);

    // Assemble output data
    SimpleOrderedMap<Object> out = new SimpleOrderedMap<>();
    if (ocrSnippets != null) {
      this.addOcrSnippets(out, keys, ocrSnippets);
    }
    return out;
  }

  private int[] getMaxPassages(String[] fieldNames, SolrParams params) {
    int[] maxPassages = new int[fieldNames.length];
    for (int i = 0; i < fieldNames.length; i++) {
      maxPassages[i] = params.getFieldInt(fieldNames[i], HighlightParams.SNIPPETS, 1);
    }
    return maxPassages;
  }

  private void addOcrSnippets(
      NamedList<Object> out, String[] keys, OcrHighlightResult[] ocrSnippets) {
    for (int k = 0; k < keys.length; k++) {
      String docId = keys[k];
      SimpleOrderedMap<Object> docMap = (SimpleOrderedMap<Object>) out.get(docId);
      if (docMap == null) {
        docMap = new SimpleOrderedMap<>();
      }
      if (ocrSnippets[k] == null) {
        continue;
      }
      docMap.addAll(ocrSnippets[k].toNamedList());
      if (docMap.size() > 0) {
        out.add(docId, docMap);
      }
    }
  }

  /** Obtain all fields among the requested fields that contain OCR data. */
  private String[] getOcrHighlightFields(SolrQueryRequest req) {
    String[] fields = req.getParams().getParams(OcrHighlightParams.OCR_FIELDS);

    if (fields != null && fields.length > 0) {
      Set<String> expandedFields = new LinkedHashSet<>();
      Collection<String> storedHighlightFieldNames =
          req.getSearcher().getDocFetcher().getStoredHighlightFieldNames();
      for (String field : fields) {
        expandWildcardsInHighlightFields(
            expandedFields, storedHighlightFieldNames, SolrPluginUtils.split(field));
      }
      if (expandedFields.isEmpty()) {
        log.warn(
            "Requested to highlight fields '{}', but no matching stored fields were found, check your query and schema!",
            String.join(",", fields));
      }
      fields = expandedFields.toArray(new String[] {});
      // Trim them now in case they haven't been yet.  Not needed for all code-paths above but do it
      // here.
      for (int i = 0; i < fields.length; i++) {
        fields[i] = fields[i].trim();
      }
    }
    return fields;
  }

  /**
   * Copied from {@link
   * org.apache.solr.highlight.SolrHighlighter#expandWildcardsInHighlightFields(java.util.Set,
   * java.util.Collection, java.lang.String...)} due to private access there. <strong>Please refer
   * to the file header for licensing information on the original code.</strong>
   */
  private static void expandWildcardsInHighlightFields(
      Set<String> expandedFields, Collection<String> storedHighlightFieldNames, String... fields) {
    for (String field : fields) {
      if (field.contains("*")) {
        // create a Java regular expression from the wildcard string
        String fieldRegex = field.replace("\\*", ".*");
        for (String storedFieldName : storedHighlightFieldNames) {
          if (storedFieldName.matches(fieldRegex)) {
            expandedFields.add(storedFieldName);
          }
        }
      } else {
        expandedFields.add(field);
      }
    }
  }
}
