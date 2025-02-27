package com.github.dbmdz.solrocr.util;

import com.github.dbmdz.solrocr.solr.OcrHighlightParams;
import java.util.concurrent.TimeUnit;
import org.apache.lucene.index.QueryTimeout;
import org.apache.solr.request.SolrQueryRequest;

public class TimeAllowedLimit implements QueryTimeout {

  private final long timeoutAt;

  public TimeAllowedLimit(SolrQueryRequest req) {
    long reqTimeAllowed = req.getParams().getLong(OcrHighlightParams.TIME_ALLOWED, -1L);
    if (reqTimeAllowed == -1L) {
      throw new IllegalArgumentException(
          "Check for limit with hasTimeLimit(req) before creating a TimeAllowedLimit");
    } else {
      long timeAllowed = reqTimeAllowed - (long) req.getRequestTimer().getTime();
      long nanosAllowed = TimeUnit.NANOSECONDS.convert(timeAllowed, TimeUnit.MILLISECONDS);
      this.timeoutAt = System.nanoTime() + nanosAllowed;
    }
  }

  public static boolean hasTimeLimit(SolrQueryRequest req) {
    return getTimeAllowed(req) >= 0L;
  }

  public static long getTimeAllowed(SolrQueryRequest req) {
    return req.getParams().getLong(OcrHighlightParams.TIME_ALLOWED, -1L);
  }

  public boolean shouldExit() {
    return this.timeoutAt - System.nanoTime() < 0L;
  }
}
