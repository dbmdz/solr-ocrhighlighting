package org.mdz.search.solrocr.solr;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.apache.lucene.analysis.util.ResourceLoader;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.uhighlight.UnifiedHighlighter;
import org.apache.lucene.search.uhighlight.UnifiedHighlighter.HighlightFlag;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.SolrException.ErrorCode;
import org.apache.solr.common.params.HighlightParams;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.common.util.SimpleOrderedMap;
import org.apache.solr.core.PluginInfo;
import org.apache.solr.core.SolrResourceLoader;
import org.apache.solr.highlight.UnifiedSolrHighlighter;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.search.DocList;
import org.apache.solr.util.plugin.PluginInfoInitialized;
import org.mdz.search.solrocr.formats.OcrBlock;
import org.mdz.search.solrocr.formats.OcrFormat;
import org.mdz.search.solrocr.formats.OcrPassageFormatter;
import org.mdz.search.solrocr.formats.OcrSnippet;
import org.mdz.search.solrocr.lucene.OcrHighlighter;
import org.mdz.search.solrocr.lucene.fieldloader.ExternalFieldLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SolrOcrHighlighter extends UnifiedSolrHighlighter {

  private static final Logger LOGGER = LoggerFactory.getLogger(SolrOcrHighlighter.class);

  public static final String NO_WEIGHT_MATCHES_SUPPORT_MSG =
      "OCR highlighting in external UTF-8 files does not support hl.weightMatches, classic highlighting approach will "
    + "be used instead. Switch to escaped ASCII or UTF-16 to avoid this.";

  private final ResourceLoader resourceLoader;

  private ExternalFieldLoader fieldLoader;
  private OcrFormat ocrFormat;
  private List<String> ocrFieldNames;

  public SolrOcrHighlighter() {
    Path libPath = SolrResourceLoader.locateSolrHome().resolve("lib");

    this.resourceLoader = new SolrResourceLoader(libPath);
  }

  @Override
  public void init(PluginInfo info) {
    super.init(info);
    String formatClsName = info.attributes.get("ocrFormat");
    if (formatClsName == null) {
      throw new SolrException(
          ErrorCode.FORBIDDEN,
          "Please configure your OCR format with the `ocrFormat` attribute on <highlighting>. "
        + "Refer to the org.mdz.search.solrocr.formats package for available formats.");
    }
    try {
      Class<?> clz = Class.forName(formatClsName);
      this.ocrFormat = (OcrFormat) clz.getConstructors()[0].newInstance();
    } catch (ClassNotFoundException e) {
      throw new SolrException(ErrorCode.FORBIDDEN, "Unknown OCR format: " + formatClsName);
    } catch (Exception e) {
      throw new SolrException(ErrorCode.FORBIDDEN, "Error loading OCR format: " + e);
    }

    NamedList<String> ocrFieldInfo = (NamedList) info.initArgs.get("ocrFields");
    if (ocrFieldInfo == null) {
      throw new SolrException(
          ErrorCode.FORBIDDEN,
          "Please define the fields that OCR highlighting should apply to in a ocrFields list in your solrconfig.xml. "
        + "Example: <lst name\"ocrFields\"><str>ocr_text</str></lst>");
    }
    this.ocrFieldNames = new ArrayList<>();
    ocrFieldInfo.forEach((k, fieldName) -> ocrFieldNames.add(fieldName));

    PluginInfo fieldLoaderInfo = info.getChild("fieldLoader");
    if (fieldLoaderInfo != null) {
      try {
        Class<?> clz = Class.forName(fieldLoaderInfo.className);
        this.fieldLoader = (ExternalFieldLoader) clz.getConstructors()[0].newInstance();
      } catch (ClassNotFoundException e) {
        throw new SolrException(ErrorCode.FORBIDDEN, "Unknown OCR format: " + formatClsName);
      } catch (Exception e) {
        throw new SolrException(ErrorCode.FORBIDDEN, "Error loading OCR format: " + e);
      }
      if (fieldLoader instanceof PluginInfoInitialized) {
        ((PluginInfoInitialized) fieldLoader).init(fieldLoaderInfo);
      }
    }
  }

  @Override
  public NamedList<Object> doHighlighting(DocList docs, Query query, SolrQueryRequest req, String[] defaultFields)
      throws IOException {
    // Copied from superclass
    // - *snip* -
    final SolrParams params = req.getParams();
    if (!isHighlightingEnabled(params))
      return null;
    int[] docIDs = toDocIDs(docs);
    String[] keys = getUniqueKeys(req.getSearcher(), docIDs);
    // - *snap* -

    // query-time parameters
    String[] ocrFieldNames = getOcrHighlightFields(query, req, defaultFields);
    String[] regularFieldNames = Stream.of(getHighlightFields(query, req, defaultFields))
        .filter(f -> Arrays.binarySearch(ocrFieldNames, f) < 0)
        .toArray(String[]::new);

    int[] maxPassagesRegular = getMaxPassages(regularFieldNames, params);
    int[] maxPassagesOcr = getMaxPassages(ocrFieldNames, params);

    // Highlight non-OCR fields
    Map<String, String[]> regularSnippets = null;
    if (regularFieldNames.length > 0) {
      UnifiedHighlighter regularHighlighter = getHighlighter(req);
      regularSnippets = regularHighlighter.highlightFields(regularFieldNames, query, docIDs, maxPassagesRegular);
    }

    Map<String, String> highlightFieldWarnings = new HashMap<>();
    Map<String, OcrSnippet[][]> ocrSnippets = null;
    // Highlight OCR fields
    if (ocrFieldNames.length > 0) {
      OcrHighlighter ocrHighlighter = new OcrHighlighter(
          req.getSearcher(), req.getSchema().getIndexAnalyzer(), fieldLoader, req.getParams());
      if (fieldLoader.getCharset() == StandardCharsets.UTF_8) {
        Arrays.stream(ocrFieldNames)
            .filter(f -> ocrHighlighter.getFlags(f).contains(HighlightFlag.WEIGHT_MATCHES))
            .forEach(field -> highlightFieldWarnings.put(field, NO_WEIGHT_MATCHES_SUPPORT_MSG));
      }
      ocrFormat.setBreakParameters(
          OcrBlock.valueOf(params.get("hl.ocr.contextBlock", "line").toUpperCase()),
          params.getInt("hl.ocr.contextSize", 2));
      BreakIterator ocrBreakIterator = ocrFormat.getBreakIterator();
      OcrPassageFormatter ocrFormatter = ocrFormat.getPassageFormatter(
          OcrBlock.valueOf(params.get("hl.ocr.limitBlock", "block").toUpperCase()),
          params.get("hl.tag.pre", "<em>"), params.get("hl.tag.post", "</em>"));
      ocrSnippets = ocrHighlighter.highlightOcrFields(
          ocrFieldNames, query, docIDs, maxPassagesOcr, ocrBreakIterator, ocrFormatter);
    }

    // Assemble output data
    NamedList<Object> out = new NamedList<>();
    if (regularSnippets != null) {
      out.addAll(this.encodeSnippets(keys, regularFieldNames, regularSnippets));
    }
    if (ocrSnippets != null) {
      this.addOcrSnippets(out, keys, ocrFieldNames, ocrSnippets);
    }
    if (!highlightFieldWarnings.isEmpty()) {
      SimpleOrderedMap<String> hlWarnings = new SimpleOrderedMap<>();
      highlightFieldWarnings.forEach(hlWarnings::add);
      out.add("warnings", hlWarnings);
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

  private void addOcrSnippets(NamedList<Object> out, String[] keys, String[] ocrFieldNames,
                              Map<String, OcrSnippet[][]> ocrSnippets) {
    for (int k=0; k < keys.length; k++) {
      String docId = keys[k];
      SimpleOrderedMap docMap = (SimpleOrderedMap) out.get(docId);
      if (docMap == null) {
        docMap = new SimpleOrderedMap();
        out.add(docId, docMap);
      }
      for (String fieldName : ocrFieldNames) {
        OcrSnippet[] snips = ocrSnippets.get(fieldName)[k];
        if (snips == null) {
          continue;
        }
        NamedList[] outSnips = new SimpleOrderedMap[snips.length];
        for (int s = 0; s < snips.length; s++) {
          OcrSnippet snip = snips[s];
          outSnips[s] = snip.toNamedList();
        }
        docMap.add(fieldName, outSnips);
      }
    }
  }

  /** Obtain all fields among the requested fields that contain OCR data. */
  private String[] getOcrHighlightFields(Query query, SolrQueryRequest req, String[] defaultFields) {
    return Arrays.stream(getHighlightFields(query, req, defaultFields))
        .distinct()
        .filter(ocrFieldNames::contains)
        .toArray(String[]::new);
  }
}
