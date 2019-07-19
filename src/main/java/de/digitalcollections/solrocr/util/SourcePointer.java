package de.digitalcollections.solrocr.util;

import com.google.common.collect.ImmutableList;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class SourcePointer {
  public static class FileSource {
    public Path path;
    public List<Region> regions;

    public FileSource(Path path, List<Region> regions) {
      this.path = path;
      this.regions = regions;
    }
  }

  public static class Region {
    public int start;
    public int end;
    public int startOffset = 0;

    public Region(int start, int end) {
      this.start = start;
      this.end = end;
    }

    public Region(int start, int end, int startOffset) {
      this(start, end);
      this.startOffset = startOffset;
    }
  }

  private static final Pattern POINTER_PAT = Pattern.compile("^(?<path>.+?)(?:\\[(?<regions>[0-9:,]+)])?$");

  public List<FileSource> sources;

  public static boolean isPointer(String pointer) {
    Matcher m = POINTER_PAT.matcher(pointer);
    return m.matches();
  }

  public static SourcePointer parse(String pointer) {
    Matcher m = POINTER_PAT.matcher(pointer);
    if (!m.matches()) {
      // TODO: Abort with error
      return null;
    }
    return new SourcePointer(Arrays.stream(pointer.split("\\+"))
        .map(ptr -> {
          Path sourcePath = Paths.get(m.group("path"));
          List<SourcePointer.Region> regions = ImmutableList.of();
          if (m.group("regions") != null) {
            regions = Arrays.stream(m.group("regions").split(","))
                .map(SourcePointer::parseRegion)
                .sorted(Comparator.comparingInt(r -> r.start))
                .collect(Collectors.toList());
          }
          return new FileSource(sourcePath, regions);
        }).collect(Collectors.toList()));
  }

  private static SourcePointer.Region parseRegion(String r) {
    if (r.startsWith(":")) {
      return new SourcePointer.Region(0, Integer.parseInt(r.substring(1)));
    } else if (r.endsWith(":")) {
      return new SourcePointer.Region(Integer.parseInt(r.substring(0, r.length() - 1)), Integer.MAX_VALUE);
    } else {
      String[] offsets = r.split(":");
      return new SourcePointer.Region(Integer.parseInt(offsets[0]), Integer.parseInt(offsets[1]));
    }
  }

  public SourcePointer(List<FileSource> sources) {
    this.sources = sources;
  }
}
