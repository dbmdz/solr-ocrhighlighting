package de.digitalcollections.solrocr.util;

import java.util.concurrent.TimeUnit;
import org.apache.lucene.index.QueryTimeout;

public class HighlightTimeout implements QueryTimeout {
  public static final ThreadLocal<Long> timeoutAt = new ThreadLocal<>();

  private static final HighlightTimeout instance = new HighlightTimeout();

  public static HighlightTimeout getInstance() {
    return instance;
  }

  private HighlightTimeout() { }

  public static Long get() {
    return timeoutAt.get();
  }

  public static void set(Long timeAllowed) {
    timeoutAt.set(System.nanoTime() + TimeUnit.NANOSECONDS.convert(timeAllowed, TimeUnit.MILLISECONDS));
  }

  public static void reset() {
    timeoutAt.remove();
  }

  @Override
  public boolean shouldExit() {
    Long timeoutAt = get();
    if (timeoutAt == null) {
      return false;
    }
    return timeoutAt - System.nanoTime() < 0L;
  }

  @Override
  public boolean isTimeoutEnabled() {
    return get() != null;
  }

  @Override
  public String toString() {
    return "timeoutAt: " + get() + " (System.nanoTime(): " + System.nanoTime() + ")";
  }
}
