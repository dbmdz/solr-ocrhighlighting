package solrocr;

import com.github.dbmdz.solrocr.lucene.filters.ExternalUtf8ContentFilter;
import com.github.dbmdz.solrocr.model.SourcePointer;
import com.github.dbmdz.solrocr.model.SourcePointer.Region;
import com.github.dbmdz.solrocr.model.SourcePointer.Source;
import com.github.dbmdz.solrocr.model.SourcePointer.SourceType;
import com.github.dbmdz.solrocr.reader.SourceReader;
import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
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
import org.apache.solr.common.SolrException;
import org.apache.solr.common.SolrException.ErrorCode;

/**
 * A CharFilter implementation that loads the field value from an external UTF8-encoded source and
 * maps Java character offsets to their correct UTF8 byte-offsets in the source.
 *
 * <p>For more information on these source pointers, refer to {@link SourcePointer}.
 */
public class ExternalUtf8ContentFilterFactory extends CharFilterFactory {
  public ExternalUtf8ContentFilterFactory(Map<String, String> args) {
    super(args);
    // TODO: Read allowed base directories from config
    // TODO: Read allowed filename patterns from config
    // TODO: Warn of security implications if neither is defined
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
      pointer.sources.forEach(this::validateSource);

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
      SourceReader r = pointer.getReader(512 * 1024, 0);
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

  private void validateSource(Source src) {
    // TODO: Check if sourcePath is located under one of the allowed base directories, else abort
    // TODO: Check if sourcePath's filename matches one of the allowed filename patterns, else abort
    if (src.type == SourceType.FILESYSTEM) {
      File f = Paths.get(src.target).toFile();
      if (!f.exists() || !f.canRead()) {
        throw new SolrException(
            ErrorCode.BAD_REQUEST,
            String.format(
                Locale.US, "File at %s either does not exist or cannot be read.", src.target));
      }
    } else {
      throw new SolrException(
          ErrorCode.BAD_REQUEST,
          String.format(Locale.US, "Pointer has target with unsupported type: %s", src.target));
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
      try (SourceReader reader = src.getReader(512, 0)) {
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
