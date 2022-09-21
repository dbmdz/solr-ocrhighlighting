package com.github.dbmdz.solrocr.model;

import com.google.common.collect.ImmutableList;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class SourcePointer {

  public static class FileSource {

    public final Path path;
    public List<Region> regions;
    public boolean isAscii;

    public FileSource(Path path, List<Region> regions, boolean isAscii) throws IOException {
      this.path = path;
      if (!path.toFile().exists()) {
        throw new FileNotFoundException(
            String.format(Locale.US, "File at %s does not exist.", path));
      }
      if (path.toFile().length() == 0) {
        throw new IOException(String.format(Locale.US, "File at %s is empty.", path));
      }
      this.regions = regions;
      this.isAscii = isAscii;
    }

    static FileSource parse(String pointer) {
      Matcher m = POINTER_PAT.matcher(pointer);
      if (!m.find()) {
        throw new RuntimeException("Could not parse source pointer from '" + pointer + ".");
      }
      Path sourcePath = Paths.get(m.group("path"));
      List<Region> regions = ImmutableList.of();
      if (m.group("regions") != null) {
        regions =
            Arrays.stream(m.group("regions").split(","))
                .map(Region::parse)
                .sorted(Comparator.comparingInt(r -> r.start))
                .collect(Collectors.toList());
      }
      try {
        return new FileSource(sourcePath, regions, m.group("isAscii") != null);
      } catch (FileNotFoundException e) {
        throw new RuntimeException("Could not locate file at '" + sourcePath + ".");
      } catch (IOException e) {
        throw new RuntimeException("Could not read file at '" + sourcePath + ".");
      }
    }

    public String toString() {
      StringBuilder sb = new StringBuilder(path.toString());
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
      Pattern.compile("^(?<path>.+?)(?<isAscii>\\{ascii})?(?:\\[(?<regions>[0-9:,]+)])?$");

  public final List<FileSource> sources;

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
    List<FileSource> fileSources =
        Arrays.stream(sourceTokens).map(FileSource::parse).collect(Collectors.toList());
    if (fileSources.isEmpty()) {
      return null;
    } else {
      return new SourcePointer(fileSources);
    }
  }

  public SourcePointer(List<FileSource> sources) {
    this.sources = sources;
  }

  /**
   * Create meaningful human-readable representation of {@link SourcePointer} from it's attached
   * files
   */
  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    for (FileSource source : this.sources) {
      if (sb.length() > 0) {
        sb.append("+");
      }
      sb.append(source.toString());
    }
    return sb.toString();
  }
}
