package org.mdz.search.solrocr.solr;

import org.apache.solr.common.params.HighlightParams;

public interface OcrHighlightParams extends HighlightParams {
  String CONTEXT_BLOCK = "hl.ocr.contextBlock";
  String CONTEXT_SIZE = "hl.ocr.contextSize";
  String LIMIT_BLOCK = "hl.ocr.limitBlock";
  String PAGE_ID = "hl.ocr.pageId";
  String SCORE_BOOST_EARLY = "hl.score.boostEarly";
}
