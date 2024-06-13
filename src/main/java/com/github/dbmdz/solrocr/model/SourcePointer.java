package com.github.dbmdz.solrocr.model;

import com.github.dbmdz.solrocr.reader.FileSourceReader;
import com.github.dbmdz.solrocr.reader.MultiFileSourceReader;
import com.github.dbmdz.solrocr.reader.SourceReader;
import com.google.common.collect.ImmutableList;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SourcePointer {
  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  public enum SourceType {
    FILESYSTEM,
  };

  public static class Source {

    public final SourceType type;
    public final String target;
    public List<Region> regions;
    public boolean isAscii;

    public Source(String target, List<Region> regions, boolean isAscii) throws IOException {
      this.type = determineType(target);
      Source.validateTarget(target, this.type);
      this.target = target;
      this.regions = regions;
      this.isAscii = isAscii;
    }

    static SourceType determineType(String target) throws IOException {
      if (target.startsWith("/")) {
        return SourceType.FILESYSTEM;
      } else if (Files.exists(Paths.get(target))) {
        return SourceType.FILESYSTEM;
      } else {
        throw new IOException(
            String.format(Locale.US, "Target %s is currently not supported.", target));
      }
    }

    static void validateTarget(String target, SourceType type) throws IOException {
      if (type == SourceType.FILESYSTEM) {
        Path path = Paths.get(target);
        if (!Files.exists(path)) {
          throw new FileNotFoundException(
              String.format(Locale.US, "File at %s does not exist.", target));
        }
        if (Files.size(path) == 0) {
          throw new IOException(String.format(Locale.US, "File at %s is empty.", target));
        }
      } else {
        throw new IOException(
            String.format(Locale.US, "Target %s is currently not supported.", target));
      }
    }

    static Source parse(String pointer) {
      Matcher m = POINTER_PAT.matcher(pointer);
      if (!m.find()) {
        throw new RuntimeException("Could not parse source pointer from '" + pointer + ".");
      }
      String target = m.group("target");
      List<Region> regions = ImmutableList.of();
      if (m.group("regions") != null) {
        regions =
            Arrays.stream(m.group("regions").split(","))
                .map(Region::parse)
                .sorted(Comparator.comparingInt(r -> r.start))
                .collect(Collectors.toList());
      }
      try {
        return new Source(target, regions, m.group("isAscii") != null);
      } catch (FileNotFoundException e) {
        throw new RuntimeException("Could not locate file at '" + target + ".");
      } catch (IOException e) {
        throw new RuntimeException("Could not read target at '" + target + ".");
      }
    }

    public SourceReader getReader(int sectionSize, int maxCacheEntries) throws IOException {
      if (this.type == SourceType.FILESYSTEM) {
        return new FileSourceReader(
            Paths.get(this.target), SourcePointer.parse(this.target), sectionSize, maxCacheEntries);
      } else {
        throw new UnsupportedOperationException("Unsupported source type '" + this.type + "'.");
      }
    }

    public String toString() {
      StringBuilder sb = new StringBuilder(target);
      if (isAscii) {
        sb.append("{ascii}");
      }
      if (!regions.isEmpty()) {
        sb.append("[");
        for (Region region : regions) {
          if (sb.charAt(sb.length() - 1) != '[') {
            sb.append(",");
          }
          sb.append(region.toString());
        }
        sb.append("]");
      }
      return sb.toString();
    }
  }

  public static class Region {

    public int start;
    public int end;
    public int startOffset = 0;

    public static Region parse(String r) {
      if (r.startsWith(":")) {
        return new SourcePointer.Region(0, Integer.parseInt(r.substring(1)));
      } else if (r.endsWith(":")) {
        return new SourcePointer.Region(Integer.parseInt(r.substring(0, r.length() - 1)), -1);
      } else {
        String[] offsets = r.split(":");
        return new SourcePointer.Region(Integer.parseInt(offsets[0]), Integer.parseInt(offsets[1]));
      }
    }

    public Region(int start, int end) {
      this.start = start;
      this.end = end;
    }

    public Region(int start, int end, int startOffset) {
      this(start, end);
      this.startOffset = startOffset;
    }

    @Override
    public String toString() {
      return start + ":" + end;
    }
  }

  static final Pattern POINTER_PAT =
      Pattern.compile("^(?<target>.+?)(?<isAscii>\\{ascii})?(?:\\[(?<regions>[0-9:,]+)])?$");

  public final List<Source> sources;

  public static boolean isPointer(String pointer) {
    if (pointer.startsWith("<")) {
      return false;
    }
    return Arrays.stream(pointer.split("\\+"))
        .allMatch(pointerToken -> POINTER_PAT.matcher(pointerToken).matches());
  }

  public static SourcePointer parse(String pointer) {
    if (!isPointer(pointer)) {
      throw new RuntimeException("Could not parse pointer: " + pointer);
    }
    String[] sourceTokens = pointer.split("\\+");
    List<Source> sources =
        Arrays.stream(sourceTokens).map(Source::parse).collect(Collectors.toList());
    if (sources.isEmpty()) {
      return null;
    } else {
      return new SourcePointer(sources);
    }
  }

  public SourcePointer(List<Source> sources) {
    this.sources = sources;
  }

  @Override
  public String toString() {
    return sources.stream().map(Source::toString).collect(Collectors.joining("+"));
  }

  /** Create a reader for the data pointed at by this source pointer. */
  public SourceReader getReader(int sectionSize, int maxCacheEntries) throws IOException {
    if (this.sources.stream().allMatch(s -> s.type == SourceType.FILESYSTEM)) {
      if (this.sources.size() == 1) {
        return new FileSourceReader(
            Paths.get(this.sources.get(0).target), this, sectionSize, maxCacheEntries);
      } else {
        return new MultiFileSourceReader(
            this.sources.stream().map(s -> Paths.get(s.target)).collect(Collectors.toList()),
            this,
            sectionSize,
            maxCacheEntries);
      }
    } else {
      throw new IOException(
          String.format(
              Locale.US,
              "Pointer %s contains unsupported target types or a mix of target types.",
              this));
    }
  }
}
