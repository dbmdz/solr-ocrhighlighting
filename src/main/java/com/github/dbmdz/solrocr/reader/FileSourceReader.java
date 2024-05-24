package com.github.dbmdz.solrocr.reader;

import com.github.dbmdz.solrocr.model.SourcePointer;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Reads from a single file source using a {@link FileChannel}. */
public class FileSourceReader extends BaseSourceReader {
  private final Path path;
  private final FileChannel chan;
  private int fileSizeBytes = -1;

  public FileSourceReader(Path path, SourcePointer ptr, int sectionSize, int maxCacheEntries)
      throws IOException {
    super(ptr, sectionSize, maxCacheEntries);
    this.path = path;
    this.chan = (FileChannel) Files.newByteChannel(path, StandardOpenOption.READ);
  }

  @Override
  protected int readBytes(byte[] dst, int dstOffset, int start, int len) throws IOException {
    return this.chan.read(ByteBuffer.wrap(dst, dstOffset, len), start);
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
}
