package com.github.dbmdz.solrocr.reader;

import com.github.dbmdz.solrocr.model.SourcePointer;
import com.github.dbmdz.solrocr.util.ArrayUtils;
import io.minio.MinioClient;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Arrays;
import java.util.Collections;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads from multiple file sources, treating them as a single large chunk of data, using a {@link
 * FileChannel}.
 */
public class MultiSourceReader extends BaseSourceReader {
  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  private final SourcePointer.Source[] sources;
  private final SourceReader[] openReaders;
  private final int[] startOffsets;
  private final int numBytes;
  private final MinioClient s3Client;

  public MultiSourceReader(
      SourcePointer ptr, int sectionSize, int maxCacheEntries, MinioClient s3Client) {
    super(ptr, sectionSize, maxCacheEntries);
    this.sources = ptr.sources.toArray(new SourcePointer.Source[0]);
    this.openReaders = new SourceReader[sources.length];
    this.startOffsets = new int[sources.length];
    this.s3Client = s3Client;

    int offset = 0;
    try {
      for (int i = 0; i < ptr.sources.size(); i++) {
        startOffsets[i] = offset;
        openReaders[i] =
            new SourcePointer(Collections.singletonList(this.sources[i]))
                .getReader(sectionSize, maxCacheEntries, s3Client);
        offset += openReaders[i].length();
      }
    } catch (IOException e) {
      // Should've been caught by SourcePointer validation
      throw new RuntimeException(e);
    }
    this.numBytes = offset;
  }

  @Override
  public int readBytes(ByteBuffer dst, int start) throws IOException {
    int sourceIdx = ArrayUtils.binaryFloorIdxSearch(startOffsets, start);
    if (sourceIdx < 0) {
      throw new RuntimeException(String.format("Offset %d is out of bounds", start));
    }
    int sourceOffset = startOffsets[sourceIdx];
    if (openReaders[sourceIdx] == null) {
      openReaders[sourceIdx] =
          new SourcePointer(Collections.singletonList(this.sources[sourceIdx]))
              .getReader(this.sectionSize, this.maxCacheEntries, s3Client);
    }
    SourceReader reader = openReaders[sourceIdx];

    int len = dst.remaining();
    int numReadTotal = 0;
    while (numReadTotal < len) {
      int numRead = reader.readBytes(dst, (start + numReadTotal) - sourceOffset);
      if (numRead == -1) {
        sourceIdx++;
        // EOF: no more sources to read
        if (sourceIdx >= this.sources.length) {
          if (numReadTotal == 0) {
            return -1;
          }
          return numReadTotal;
        }
        if (openReaders[sourceIdx] == null) {
          openReaders[sourceIdx] =
              new SourcePointer(Collections.singletonList(this.sources[sourceIdx]))
                  .getReader(this.sectionSize, this.maxCacheEntries, s3Client);
        }
        reader = openReaders[sourceIdx];
        sourceOffset = startOffsets[sourceIdx];
        continue;
      }
      numReadTotal += numRead;
    }
    return numReadTotal;
  }

  @Override
  public int length() {
    return this.numBytes;
  }

  @Override
  public void close() throws IOException {
    for (SourceReader reader : openReaders) {
      if (reader == null) {
        continue;
      }
      try {
        reader.close();
      } catch (IOException e) {
        log.error(
            String.format(
                "Failed to close file at %s: %s",
                reader.getPointer().sources.get(0), e.getMessage()),
            e);
      }
    }
  }

  @Override
  public String getIdentifier() {
    return String.format(
        "{%s}", Arrays.stream(sources).map(src -> src.target).collect(Collectors.joining(", ")));
  }
}
