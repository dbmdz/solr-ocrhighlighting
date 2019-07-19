package de.digitalcollections.solrocr.lucene;

import com.google.common.io.CharStreams;
import de.digitalcollections.solrocr.util.Region;
import de.digitalcollections.solrocr.util.Utf8;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.lucene.analysis.util.CharFilterFactory;

public class ExternalUtf8ContentFilterFactory extends CharFilterFactory {

  private static final Pattern POINTER_PAT = Pattern.compile("^(?<path>.+?)(?:\\[(?<regions>[0-9:,]+)])?$");


  public ExternalUtf8ContentFilterFactory(Map<String, String> args) {
    super(args);
    // TODO: Read whitelisted base directories from config
    // TODO: Read whitelisted file name patterns from config
    // TODO: Warn of security implications if neither is defined
  }

  @Override
  public Reader create(Reader input) {
    try {
      String pointer = CharStreams.toString(input);
      Matcher m = POINTER_PAT.matcher(pointer);
      if (!m.matches()) {
        // TODO: Abort with error
        return null;
      }
      Path sourcePath = Paths.get(m.group("path"));
      // TODO: Check if sourcePath is located under one of the whitelisted base directories, abort otherwise
      // TODO: Check if sourcePath's filename matches one of the whitelisted file name patterns, abort otherwise
      // TODO: Check if sourcePath exists, abort otherwise
      if (m.group("regions") != null) {
        List<Region> regions = Arrays.stream(m.group("regions").split(","))
            .map(this::parseRegion)
            .sorted(Comparator.comparingInt(r -> r.start))
            .collect(Collectors.toList());
        List<Region> charRegions = toCharOffsets(sourcePath, regions);
        return new Utf8RegionMappingCharFilter(
            new BufferedReader(new FileReader(sourcePath.toFile())), charRegions);
      } else {
        return new Utf8MappingCharFilter(new BufferedReader(new FileReader(sourcePath.toFile())));
      }
    } catch (IOException e) {
      return null;
    }
  }

  private Region parseRegion(String r) {
    if (r.startsWith(":")) {
      return new Region(0, Integer.parseInt(r.substring(1)));
    } else if (r.endsWith(":")) {
      return new Region(Integer.parseInt(r.substring(0, r.length() - 1)), Integer.MAX_VALUE);
    } else {
      String[] offsets = r.split(":");
      return new Region(Integer.parseInt(offsets[0]), Integer.parseInt(offsets[1]));
    }
  }

  private List<Region> toCharOffsets(Path sourcePath, List<Region> regions)
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
        .map(r -> new Region(offsetMap.get(r.start), offsetMap.get(r.end), r.start))
        .collect(Collectors.toList());
  }
}
