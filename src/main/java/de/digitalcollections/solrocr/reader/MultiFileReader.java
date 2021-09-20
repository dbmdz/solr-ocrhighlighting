package de.digitalcollections.solrocr.reader;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Queue;

public class MultiFileReader extends Reader {
  private final Queue<Path> remainingSources;
  private Reader currentReader;

  public MultiFileReader(List<Path> sourcePaths) throws FileNotFoundException {
    for (Path path : sourcePaths) {
      if (!path.toFile().exists()) {
        throw new FileNotFoundException(
            String.format(Locale.US, "File at %s could not be found", path));
      } else if (path.toFile().isDirectory()) {
        throw new FileNotFoundException(
            String.format(Locale.US, "File at %s is a directory", path));
      }
    }
    this.remainingSources = new LinkedList<>(sourcePaths);
    this.currentReader =
        new InputStreamReader(
            new FileInputStream(remainingSources.remove().toFile()), StandardCharsets.UTF_8);
  }

  @Override
  public int read(char[] cbuf, int off, int len) throws IOException {
    if (this.currentReader == null) {
      // No readers available, nothing to read
      return -1;
    }
    int numRead = 0;
    while (numRead < len && currentReader != null) {
      int read = this.currentReader.read(cbuf, off, len);
      if (read < len) {
        if (this.remainingSources.isEmpty()) {
          // No more readers, return what was read so far
          this.currentReader = null;
        } else {
          this.currentReader =
              new InputStreamReader(
                  new FileInputStream(remainingSources.remove().toFile()), StandardCharsets.UTF_8);
        }
      }
      if (read < 0) {
        continue;
      }
      numRead += read;
      off += read;
      len -= read;
    }
    return numRead > 0 ? numRead : -1;
  }

  @Override
  public void close() throws IOException {
    if (this.currentReader != null) {
      this.currentReader.close();
    }
  }
}
