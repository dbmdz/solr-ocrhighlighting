package solrocr;

import com.github.dbmdz.solrocr.lucene.filters.ExternalUtf8ContentFilter;
import com.github.dbmdz.solrocr.model.S3Config;
import com.github.dbmdz.solrocr.model.SourcePointer;
import com.github.dbmdz.solrocr.model.SourcePointer.Region;
import com.github.dbmdz.solrocr.model.SourcePointer.Source;
import com.github.dbmdz.solrocr.model.SourcePointer.SourceType;
import com.github.dbmdz.solrocr.reader.FileSourceReader;
import com.github.dbmdz.solrocr.reader.S3ObjectSourceReader;
import com.github.dbmdz.solrocr.reader.SourceReader;
import io.minio.MinioClient;
import io.minio.errors.*;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Paths;
import java.util.Arrays;
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

      if (pointer.sources.isEmpty()) {
        throw new RuntimeException(
            "No source files could be determined from pointer. "
                + "Is it pointing to files that exist and are readable? "
                + "Pointer was: "
                + ptrStr);
      }
      adjustRegions(pointer);
      // Section size and cache size don't matter, since we don't use sectioned reads during
      // indexing.
      SourceReader r = pointer.getReader(512 * 1024, 0, s3Client);
      List<SourcePointer.Region> regions =
          pointer.sources.stream().flatMap(s -> s.regions.stream()).collect(Collectors.toList());
      return new ExternalUtf8ContentFilter(r.getByteChannel(), regions, ptrStr);
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

  /**
   * Adjust regions to account for UTF BOM, if present, and to make them relative to the
   * concatenated inputs.
   *
   * <p>UTF8-encoded files may contain a 3 byte byte-order-marker at the beginning of the file. Its
   * use is discouraged and not needed for UTF8 (since the byte order is pre-defined), but we've
   * encountered OCR files in the wild that have it, so we check for it and adjust regions starting
   * on the beginning of the file to account for it.
   */
  private void adjustRegions(SourcePointer ptr) throws IOException {
    int outByteOffset = 0;
    byte[] bomBuf = new byte[3];
    for (SourcePointer.Source src : ptr.sources) {
      // Again, section size and cache size don't matter, since we don't use sectioned reads during
      // indexing.
      try (SourceReader reader = src.getReader(512, 0, s3Client)) {
        int inputLen = reader.length();

        if (src.regions.isEmpty()) {
          src.regions.add(new Region(0, inputLen));
        }

        if (!src.isAscii && src.regions.stream().anyMatch(r -> r.start == 0)) {
          SeekableByteChannel chan = reader.getByteChannel();
          chan.read(ByteBuffer.wrap(bomBuf));
          chan.position(0);
          boolean hasBOM =
              Arrays.equals(bomBuf, new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
          if (hasBOM) {
            src.regions.stream().filter(r -> r.start == 0).forEach(r -> r.start += 3);
          }
        }

        for (Region region : src.regions) {
          if (region.end == -1) {
            region.end = inputLen;
          }
          region.start += outByteOffset;
          region.end += outByteOffset;
        }

        outByteOffset += inputLen;
      }
    }
  }
}
