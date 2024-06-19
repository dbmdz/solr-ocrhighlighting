package solrocr;

import com.github.dbmdz.solrocr.lucene.filters.ExternalUtf8ContentFilter;
import com.github.dbmdz.solrocr.model.S3Config;
import com.github.dbmdz.solrocr.model.SourcePointer;
import com.github.dbmdz.solrocr.model.SourcePointer.Source;
import com.github.dbmdz.solrocr.model.SourcePointer.SourceType;
import com.github.dbmdz.solrocr.reader.FileSourceReader;
import com.github.dbmdz.solrocr.reader.S3ObjectSourceReader;
import com.github.dbmdz.solrocr.reader.SourceReader;
import com.github.dbmdz.solrocr.util.Utf8;
import com.google.common.collect.ImmutableList;
import io.minio.MinioClient;
import io.minio.errors.*;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.commons.io.IOUtils;
import org.apache.lucene.analysis.CharFilterFactory;

/**
 * A CharFilter implementation that loads the field value from an external UTF8-encoded source and
 * maps Java character offsets to their correct UTF8 byte-offsets in the source.
 *
 * <p>For more information on these source pointers, refer to {@link SourcePointer}.
 */
public class ExternalUtf8ContentFilterFactory extends CharFilterFactory {
  private MinioClient s3Client = null;

  public ExternalUtf8ContentFilterFactory(Map<String, String> args) {
    super(args);
    // TODO: Read allowed base directories from config
    // TODO: Read allowed filename patterns from config
    // TODO: Warn of security implications if neither is defined
    if (args.containsKey("s3Config")) {
      try {
        S3Config s3Config = S3Config.parse(args.get("s3Config"));
        s3Client =
            MinioClient.builder()
                .endpoint(s3Config.endpoint)
                .credentials(s3Config.accessKey, s3Config.secretKey)
                .build();
      } catch (IOException e) {
        throw new RuntimeException(
            "Could not configure S3Client based on " + args.get("s3Config"), e);
      }
    }
  }

  @Override
  public Reader create(Reader input) {
    // Read the input fully to obtain the source pointer
    String ptrStr;
    try {
      ptrStr = IOUtils.toString(input);
    } catch (IOException e) {
      throw new RuntimeException("Could not read source pointer from the input.", e);
    }
    if (ptrStr.isEmpty()) {
      return new StringReader("");
    }
    try {
      SourcePointer pointer = SourcePointer.parse(ptrStr);
      if (pointer == null) {
        throw new RuntimeException(
            String.format(
                Locale.US,
                "Could not parse source pointer from field, check the format (value was: '%s')!",
                ptrStr));
      }

      // Regions contained in source pointers are defined by byte offsets.
      // We need to convert these to Java character offsets, so they can be used by the filter.
      // This is very expensive, but we need this since all IO from here on out is character-based.
      toCharOffsets(pointer);

      if (pointer.sources.isEmpty()) {
        throw new RuntimeException(
            "No source files could be determined from pointer. "
                + "Is it pointing to files that exist and are readable? "
                + "Pointer was: "
                + ptrStr);
      }
      Reader r =
          Channels.newReader(
              pointer.getReader(512 * 1024, 0, s3Client).getByteChannel(),
              StandardCharsets.UTF_8.name());
      List<SourcePointer.Region> charRegions =
          pointer.sources.stream().flatMap(s -> s.regions.stream()).collect(Collectors.toList());
      return new ExternalUtf8ContentFilter(new BufferedReader(r), charRegions, ptrStr);
    } catch (IOException e) {
      throw new RuntimeException(
          String.format(
              Locale.US, "Error while reading external content from pointer '%s': %s", ptrStr, e),
          e);
    }
  }

  public SourceReader getReader(Source source, int sectionSize, int maxCacheEntries)
      throws IOException {
    if (source.type == SourceType.FILESYSTEM) {
      return new FileSourceReader(
          Paths.get(source.target),
          SourcePointer.parse(source.target),
          sectionSize,
          maxCacheEntries);
    } else if (source.type == SourceType.S3) {
      return new S3ObjectSourceReader(
          s3Client,
          URI.create(source.target),
          SourcePointer.parse(source.target),
          sectionSize,
          maxCacheEntries);
    } else {
      throw new UnsupportedOperationException("Unsupported source type '" + source.type + "'.");
    }
  }

  private static long getUtf8DecodedLength(SeekableByteChannel chan, ByteBuffer buf, long numBytes)
      throws IOException {
    long numRead = 0;
    long decodedLength = 0;
    while (numRead < numBytes) {
      if (buf.remaining() > (numBytes - numRead)) {
        buf.limit((int) (numBytes - numRead));
      }
      int read = chan.read(buf);
      if (read < 0) {
        break;
      }
      numRead += read;
      buf.flip();
      decodedLength += Utf8.decodedLength(buf);
      buf.clear();
    }
    if (numRead < numBytes) {
      throw new IOException(
          String.format(
              Locale.US,
              "Read fewer bytes than expected (%d vs %d), check your source pointer!",
              numRead,
              numBytes));
    }
    return decodedLength;
  }

  private void toCharOffsets(SourcePointer ptr) throws IOException {
    int byteOffset = 0;
    int charOffset = 0;
    ByteBuffer buf = ByteBuffer.allocateDirect(1024 * 1024 /* 1 MiB */);
    // TODO: Use a queue for the file sources so we don't have to read until the end of the last
    //       file every time
    // TODO: Think about building the UTF8 -> UTF16 offset map right here if the mapping part should
    //       become a bottle neck
    for (SourcePointer.Source src : ptr.sources) {
      SourceReader reader = this.getReader(src, 512 * 1024, 0);
      try {
        SeekableByteChannel chan = reader.getByteChannel();
        final int size = (int) chan.size();

        int bomOffset = 0;
        if (!src.isAscii) {
          // Check for BOM without modifying channel position, we need to skip it as to not break
          // mult-file parsing
          ByteBuffer bomBuf = ByteBuffer.allocate(3);
          chan.read(bomBuf);
          chan.position(0);
          bomBuf.flip();
          if (bomBuf.equals(ByteBuffer.wrap(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}))) {
            bomOffset = 3;
          }
        }

        // Byte offset of the current file from the beginning of the first file
        final int baseOffset = byteOffset;
        if (src.regions.isEmpty()) {
          src.regions = ImmutableList.of(new SourcePointer.Region(0, size));
        }
        for (SourcePointer.Region region : src.regions) {
          if (src.isAscii) {
            // Optimization for pure-ASCII sources, where we don't need to do any mapping
            region.start += baseOffset;
            region.end = Math.min(region.end + baseOffset, size + baseOffset);
            continue;
          }
          if (region.start == 0) {
            // Skip the BOM at the start of a file, if present
            region.start += bomOffset;
          }
          // Make region offsets relative to the beginning of the first file
          region.start += baseOffset;
          if (region.end < 0) {
            region.end = size;
          }
          region.end = Math.min(region.end + baseOffset, size + baseOffset);
          // Read until the start of the region
          if (byteOffset != region.start) {
            // Read the data between the current offset and the start of the region
            int len = region.start - byteOffset;
            charOffset += (int) getUtf8DecodedLength(chan, buf, len);
            byteOffset += len;
          }

          int regionSize = region.end - region.start;
          region.start = charOffset;
          region.startOffset = byteOffset;
          // Read region, determine character offset of region end
          charOffset += (int) getUtf8DecodedLength(chan, buf, regionSize);
          byteOffset += regionSize;
          region.end = charOffset;
        }
        // Determine character offset of the end of the file
        if (src.isAscii) {
          byteOffset += size;
        } else if (byteOffset != baseOffset + size) {
          int len = (baseOffset + size) - byteOffset;
          charOffset += (int) getUtf8DecodedLength(chan, buf, len);
          byteOffset += len;
        }
      } finally {
        reader.close();
      }
    }
  }
}
