package de.digitalcollections.solrocr.solr;

import com.carrotsearch.randomizedtesting.ThreadFilter;

public class CustomThreadsFilter implements ThreadFilter {

  @Override
  public boolean reject(Thread t) {
    String threadName = t.getName();
    if (threadName.startsWith("solr-ocrhighlighting-cache-warmer-")) {
      return true;
    }
    return false;
  }
}
