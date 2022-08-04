package com.github.dbmdz.solrocr.solr;

import org.apache.solr.common.params.SolrParams;

public interface OcrHighlightParams {
  String HIGHLIGHT = "hl.ocr";
  String QPARSER = "hl.ocr.qparser";
  String Q = "hl.ocr.q";
  String TAG_PRE = "hl.ocr.tag.pre";
  String TAG_POST = "hl.ocr.tag.post";
  String OCR_FIELDS = "hl.ocr.fl";
  String CONTEXT_BLOCK = "hl.ocr.contextBlock";
  String CONTEXT_SIZE = "hl.ocr.contextSize";
  String LIMIT_BLOCK = "hl.ocr.limitBlock";
  String PAGE_ID = "hl.ocr.pageId";
  String SCORE_BOOST_EARLY = "hl.score.boostEarly";
  String ABSOLUTE_HIGHLIGHTS = "hl.ocr.absoluteHighlights";
  String MAX_OCR_PASSAGES = "hl.ocr.maxPassages";
  String SCORE_PASSAGES = "hl.ocr.scorePassages";
  String TIME_ALLOWED = "hl.ocr.timeAllowed";
  String ALIGN_SPANS = "hl.ocr.alignSpans";
  String TRACK_PAGES = "hl.ocr.trackPages";

  /**
   * Get a boolean value from a `hl.ocr.*` parameter. If no value is given for the parameter, try to
   * get the value from the corresponding `hl.*` parameter, otherwise return the default.
   */
  static boolean getBool(SolrParams params, String name, boolean defaultValue) {
    Boolean value = params.getBool(name);
    if (value == null) {
      return params.getBool(name.replace("hl.ocr", "hl"), defaultValue);
    }
    return value;
  }

  /**
   * Get a String value from a `hl.ocr.*` parameter. If no value is given for the parameter, try to
   * get the value from the corresponding `hl.*` parameter.
   */
  static String get(SolrParams params, String name) {
    String value = params.get(name);
    if (value == null) {
      return params.get(name.replace("hl.ocr", "hl"));
    }
    return value;
  }

  /**
   * Get a String value from a `hl.ocr.*` parameter. If no value is given for the parameter, try to
   * get the value from the corresponding `hl.*` parameter, otherwise return the default.
   */
  static String get(SolrParams params, String name, String defaultValue) {
    String value = OcrHighlightParams.get(params, name);
    if (value == null) {
      return defaultValue;
    }
    return value;
  }
}
