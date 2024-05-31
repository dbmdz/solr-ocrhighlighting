package com.github.dbmdz.solrocr.breaklocator;

import com.github.dbmdz.solrocr.reader.SourceReader;
import java.io.IOException;

public interface BreakLocator {
  int DONE = -1;

  int following(int offset) throws IOException;

  int preceding(int offset) throws IOException;

  SourceReader getText();
}
