package de.digitalcollections.solrocr.lucene;

import com.google.common.io.CharStreams;
import de.digitalcollections.solrocr.util.SourcePointer;
import de.digitalcollections.solrocr.util.Utf8;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
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
      SourcePointer pointer = SourcePointer.parse(CharStreams.toString(input));
      SourcePointer.FileSource source = pointer.sources.get(0);
      // TODO: Check if sourcePath is located under one of the whitelisted base directories, abort otherwise
      // TODO: Check if sourcePath's filename matches one of the whitelisted file name patterns, abort otherwise
      // TODO: Check if sourcePath exists, abort otherwise
      if (!source.regions.isEmpty()) {
        List<SourcePointer.Region> charRegions = toCharOffsets(source.path, source.regions);
        return new Utf8RegionMappingCharFilter(
            new BufferedReader(new FileReader(source.path.toFile())), charRegions);
      } else {
        return new Utf8MappingCharFilter(new BufferedReader(new FileReader(source.path.toFile())));
      }
    } catch (IOException e) {
      return null;
    }
  }

  private List<SourcePointer.Region> toCharOffsets(Path sourcePath, List<SourcePointer.Region> regions)
      throws IOException {
    Map<Integer, Integer> offsetMap = new HashMap<>();
    offsetMap.put(0, 0);
    List<Integer> regionOffsets = regions.stream()
        .flatMap(r -> Stream.of(r.start, r.end))
        .sorted()
        .collect(Collectors.toList());
    FileChannel fChan = FileChannel.open(sourcePath, StandardOpenOption.READ);
    for (Integer offset : regionOffsets) {
      int curByteOffset = (int) fChan.position();
      int curCharOffset = offsetMap.get(curByteOffset);
      byte[] dst = new byte[offset - curByteOffset];
      fChan.read(ByteBuffer.wrap(dst));
      int numChars = Utf8.decodedLength(dst);
      offsetMap.put((int) fChan.position(), curCharOffset + numChars);
    }
    return regions.stream()
        .map(r -> new SourcePointer.Region(offsetMap.get(r.start), offsetMap.get(r.end), r.start))
        .collect(Collectors.toList());
  }
}
