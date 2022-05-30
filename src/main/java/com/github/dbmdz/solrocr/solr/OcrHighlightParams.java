package com.github.dbmdz.solrocr.solr;

import org.apache.solr.common.params.HighlightParams;

public interface OcrHighlightParams extends HighlightParams {
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
}
