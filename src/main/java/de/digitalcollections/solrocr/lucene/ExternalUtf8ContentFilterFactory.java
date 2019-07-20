package de.digitalcollections.solrocr.lucene;

import com.google.common.collect.ImmutableList;
import com.google.common.io.CharStreams;
import de.digitalcollections.solrocr.util.MultiFileReader;
import de.digitalcollections.solrocr.util.SourcePointer;
import de.digitalcollections.solrocr.util.Utf8;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.lucene.analysis.util.CharFilterFactory;

public class ExternalUtf8ContentFilterFactory extends CharFilterFactory {
  public ExternalUtf8ContentFilterFactory(Map<String, String> args) {
    super(args);
    // TODO: Read whitelisted base directories from config
    // TODO: Read whitelisted file name patterns from config
    // TODO: Warn of security implications if neither is defined
  }

  @Override
  public Reader create(Reader input) {
    try {
      String ptrStr = CharStreams.toString(input);
      SourcePointer pointer = SourcePointer.parse(ptrStr);
      if (pointer == null) {
        throw new RuntimeException("Could not parse source pointer: " + ptrStr);
      }
      pointer.sources.forEach(this::validateSource);
      if (pointer.sources.size() > 1 || pointer.sources.stream().anyMatch(s -> !s.regions.isEmpty())) {
        toCharOffsets(pointer);
        Reader r;
        if (pointer.sources.size() > 1) {
          r = new MultiFileReader(pointer.sources.stream().map(s -> s.path).collect(Collectors.toList()));
        } else {
          r = new FileReader(pointer.sources.get(0).path.toFile());
        }
        List<SourcePointer.Region> charRegions = pointer.sources.stream()
            .flatMap(s -> s.regions.stream())
            .collect(Collectors.toList());
        return new Utf8RegionMappingCharFilter(new BufferedReader(r), charRegions);
      } else {
        return new Utf8MappingCharFilter(
            new BufferedReader(new FileReader(pointer.sources.get(0).path.toFile())));
      }
    } catch (IOException e) {
      return null;
    }
  }

  private void validateSource(SourcePointer.FileSource src) {
    // TODO: Check if sourcePath is located under one of the whitelisted base directories, abort otherwise
    // TODO: Check if sourcePath's filename matches one of the whitelisted file name patterns, abort otherwise
    // TODO: Check if sourcePath exists, abort otherwise
  }

  private void toCharOffsets(SourcePointer ptr) throws IOException {
    int byteOffset = 0;
    int charOffset = 0;
    // TODO: Use a queue for the file sources so we don't have to read until the end of the last file every time
    for (SourcePointer.FileSource src : ptr.sources) {
      FileChannel fChan = FileChannel.open(src.path, StandardOpenOption.READ);
      final int fSize = (int) fChan.size();

      // Offset of the current file from the beginning of the first file
      final int baseOffset = byteOffset;
      if (src.regions.isEmpty()) {
        src.regions = ImmutableList.of(new SourcePointer.Region(0, fSize));
      }
      for (SourcePointer.Region region : src.regions) {
        // Make region offsets relative to the beginning of the first file
        region.start += baseOffset;
        // The abs is to protect from overflow
        region.end = Math.min(Math.abs(region.end + baseOffset), fSize + baseOffset);
        // Read until the start of the region
        if (byteOffset != region.start) {
          // Read the data between the current offset and the start of the region
          int len = region.start - byteOffset;
          byte[] dst = new byte[len];
          byteOffset += fChan.read(ByteBuffer.wrap(dst));
          // Determine how many `char`s are in the data
          charOffset += Utf8.decodedLength(dst);
        }

        int regionSize = region.end - region.start;
        region.start = charOffset;
        region.startOffset = byteOffset;
        // Read region, determine character offsett of region end
        byte[] dst = new byte[regionSize];
        byteOffset += fChan.read(ByteBuffer.wrap(dst));
        charOffset += Utf8.decodedLength(dst);
        region.end = charOffset;
      }
      // Determine character offset of the end of the file
      if (byteOffset != baseOffset + fSize) {
        int len = (baseOffset + fSize) - byteOffset;
        byte[] dst = new byte[len];
        byteOffset += fChan.read(ByteBuffer.wrap(dst));
        charOffset += Utf8.decodedLength(dst);
      }
    }
  }
}
