package de.digitalcollections.solrocr.util;

import java.io.IOException;
import java.io.Reader;
import java.util.Optional;

public interface SourceAwareReader {
  default Optional<String> getSource() {
    return Optional.empty();
  }

  class Wrapper extends Reader implements SourceAwareReader {
    private final Reader input;

    public Wrapper(Reader input) {
      this.input = input;
    }

    @Override
    public Optional<String> getSource() {
      if (this.input instanceof SourceAwareReader) {
        return ((SourceAwareReader) this.input).getSource();
      } else {
        return Optional.empty();
      }
    }

    @Override
    public int read(char[] cbuf, int off, int len) throws IOException {
      return input.read(cbuf, off, len);
    }

    @Override
    public void close() throws IOException {
      input.close();
    }
  }
}
