package com.github.dbmdz.solrocr.reader;

import com.github.dbmdz.solrocr.model.SourcePointer;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import org.apache.solr.common.SolrException;

/** Reads from a single file source using a {@link FileChannel}. */
public class FileSourceReader extends BaseSourceReader {
  private final Path path;
  private final FileChannel chan;
  private int fileSizeBytes = -1;

  public FileSourceReader(Path path, SourcePointer ptr, int sectionSize, int maxCacheEntries)
      throws IOException {
    super(ptr, sectionSize, maxCacheEntries);
    validateSource(path);
    this.path = path;
    this.chan = (FileChannel) Files.newByteChannel(path, StandardOpenOption.READ);
  }

  @Override
  public int readBytes(ByteBuffer dst, int start) throws IOException {
    return this.chan.read(dst, start);
  }

  @Override
  public int length() throws IOException {
    if (this.fileSizeBytes < 0) {
      this.fileSizeBytes = (int) this.chan.size();
    }
    return this.fileSizeBytes;
  }

  @Override
  public void close() throws IOException {
    this.chan.close();
  }

  @Override
  public String getIdentifier() {
    return this.path.toString();
  }

  @Override
  public SeekableByteChannel getByteChannel() {
    return this.chan;
  }

  private void validateSource(Path path) {
    // TODO: Check if sourcePath is located under one of the allowed base directories, else abort
    // TODO: Check if sourcePath's filename matches one of the allowed filename patterns, else abort
    File f = path.toFile();
    if (!f.exists() || !f.canRead()) {
      throw new SolrException(
          SolrException.ErrorCode.BAD_REQUEST,
          String.format(Locale.US, "File at %s either does not exist or cannot be read.", path));
    }
  }
}
