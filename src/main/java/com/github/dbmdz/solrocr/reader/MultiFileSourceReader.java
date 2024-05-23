package com.github.dbmdz.solrocr.reader;

import com.github.dbmdz.solrocr.model.SourcePointer;
import com.github.dbmdz.solrocr.util.ArrayUtils;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MultiFileSourceReader extends BaseSourceReader {
  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  private static final class OpenFile {
    private final FileChannel channel;
    final int startOffset;
    final Path path;

    private OpenFile(Path p, int startOffset) throws IOException {
      this.path = p;
      channel = FileChannel.open(p, StandardOpenOption.READ);
      this.startOffset = startOffset;
    }

    public int read(byte[] dst, int dstOffset, int start, int len) throws IOException {
      return this.channel.read(ByteBuffer.wrap(dst, dstOffset, len), start);
    }

    public void close() throws IOException {
      this.channel.close();
    }
  }

  private final Path[] paths;
  private final OpenFile[] openFiles;
  private final int[] startOffsets;
  private final int numBytes;

  public MultiFileSourceReader(
      List<Path> paths, SourcePointer ptr, int sectionSize, int maxCacheEntries) {
    super(ptr, sectionSize, maxCacheEntries);
    this.paths = paths.toArray(new Path[0]);
    this.openFiles = new OpenFile[paths.size()];
    this.startOffsets = new int[paths.size()];
    int offset = 0;
    try {
      for (int i = 0; i < paths.size(); i++) {
        startOffsets[i] = offset;
        offset += (int) Files.size(this.paths[i]);
      }
    } catch (IOException e) {
      // Should've been caught by SourcePointer validation
      throw new RuntimeException(e);
    }
    this.numBytes = offset;
  }

  @Override
  protected int readBytes(byte[] dst, int dstOffset, int start, int len) throws IOException {
    int fileIdx = ArrayUtils.binaryFloorIdxSearch(startOffsets, start);
    if (fileIdx < 0) {
      throw new RuntimeException(String.format("Offset %d is out of bounds", start));
    }
    int fileOffset = startOffsets[fileIdx];
    if (openFiles[fileIdx] == null) {
      openFiles[fileIdx] = new OpenFile(paths[fileIdx], fileOffset);
    }
    OpenFile file = openFiles[fileIdx];

    int numRead = 0;
    while (numRead < len) {
      numRead += file.read(dst, dstOffset + numRead, (start + numRead) - fileOffset, len - numRead);
      if (numRead < len) {
        fileIdx++;
        if (fileIdx >= paths.length) {
          break;
        }
        if (openFiles[fileIdx] == null) {
          openFiles[fileIdx] = new OpenFile(paths[fileIdx], start + numRead);
        }
        file = openFiles[fileIdx];
        fileOffset = startOffsets[fileIdx];
      }
    }
    return numRead;
  }

  @Override
  public int length() {
    return this.numBytes;
  }

  @Override
  public void close() throws IOException {
    for (OpenFile file : openFiles) {
      if (file == null) {
        continue;
      }
      try {
        file.close();
      } catch (IOException e) {
        log.error(String.format("Failed to close file at %s: %s", file.path, e.getMessage()), e);
      }
    }
  }

  @Override
  public String getIdentifier() {
    return String.format(
        "{%s}",
        Arrays.stream(paths)
            .map(p -> p.toAbsolutePath().toString())
            .collect(Collectors.joining(", ")));
  }
}
