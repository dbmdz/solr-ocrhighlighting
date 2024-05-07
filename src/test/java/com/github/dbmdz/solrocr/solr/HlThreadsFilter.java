package com.github.dbmdz.solrocr.solr;

import com.carrotsearch.randomizedtesting.ThreadFilter;

public class HlThreadsFilter implements ThreadFilter {

  @Override
  public boolean reject(Thread thread) {
    return thread.getName().startsWith("OcrHighlighter-");
  }
}
