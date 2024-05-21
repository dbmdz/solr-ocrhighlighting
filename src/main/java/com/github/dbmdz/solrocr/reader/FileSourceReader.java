package com.github.dbmdz.solrocr.reader;

import com.github.dbmdz.solrocr.model.SourcePointer;
import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public class FileSourceReader extends BaseSourceReader {
  private final Path filePath;
  private final FileChannel chan;
  private final MappedByteBuffer buf;
  private final int fileSizeBytes;

  public FileSourceReader(Path filePath, SourcePointer ptr, int sectionSize, int maxCacheEntries) {
    super(ptr, sectionSize, maxCacheEntries);
    this.filePath = filePath;
    try {
      this.chan = (FileChannel) Files.newByteChannel(filePath, StandardOpenOption.READ);
      this.fileSizeBytes = (int) chan.size();
      this.buf = chan.map(FileChannel.MapMode.READ_ONLY, 0, fileSizeBytes);
    } catch (IOException e) {
      // Should have been caught by SourcePointer validation
      throw new RuntimeException(e);
    }
  }

  @Override
  protected int readBytes(byte[] dst, int dstOffset, int start, int len) {
    this.buf.mark();
    this.buf.position(start);
    int readLen = Math.min(len, this.length() - start);
    this.buf.get(dst, dstOffset, len);
    this.buf.reset();
    return readLen;
  }

  @Override
  public int length() {
    return this.fileSizeBytes;
  }

  @Override
  public void close() {
    try {
      this.chan.close();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public String getIdentifier() {
    return this.filePath.toAbsolutePath().toString();
  }
}
