package com.github.dbmdz.solrocr.reader;

import com.github.dbmdz.solrocr.model.SourcePointer;
import com.github.dbmdz.solrocr.util.ArrayUtils;
import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileChannel.MapMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class MultiFileSourceReader extends BaseSourceReader {
  private final Path[] paths;
  private final OpenFile[] openFiles;
  private final int[] startOffsets;
  private final int numBytes;

  private static final class OpenFile {
    private final Path path;
    private final FileChannel channel;
    private final MappedByteBuffer mappedByteBuffer;
    private final int numBytes;
    final int startOffset;

    private OpenFile(Path path, int startOffset) {
      this.path = path;
      this.startOffset = startOffset;
      try {
        this.channel = (FileChannel) Files.newByteChannel(path, StandardOpenOption.READ);
        this.numBytes = (int) channel.size();
        this.mappedByteBuffer = channel.map(MapMode.READ_ONLY, 0, numBytes);
      } catch (IOException e) {
        // Should've been caught by source pointer validation
        throw new RuntimeException(e);
      }
    }

    int read(byte[] dst, int dstOffset, int start, int len) {
      if (dst.length < dstOffset + len) {
        throw new IllegalArgumentException("Destination buffer is too small");
      }
      int readLen = Math.min(len, numBytes - start);
      this.mappedByteBuffer.mark();
      this.mappedByteBuffer.position(start);
      this.mappedByteBuffer.get(dst, dstOffset, readLen);
      this.mappedByteBuffer.reset();
      return readLen;
    }

    public void close() {
      try {
        this.channel.close();
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }
  }

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
  protected int readBytes(byte[] dst, int dstOffset, int start, int len) {
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
  public void close() {
    for (OpenFile file : openFiles) {
      if (file == null) {
        continue;
      }
      file.close();
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
