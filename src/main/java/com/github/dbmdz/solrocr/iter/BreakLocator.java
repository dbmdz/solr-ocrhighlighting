package com.github.dbmdz.solrocr.iter;

import com.github.dbmdz.solrocr.reader.SourceReader;

public interface BreakLocator {
  int DONE = -1;

  int following(int offset);

  int preceding(int offset);

  SourceReader getText();
}
