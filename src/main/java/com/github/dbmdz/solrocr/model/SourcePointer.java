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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SourcePointer {

  private static final Logger logger = LoggerFactory.getLogger(SourcePointer.class);

  public static class FileSource {
    public final Path path;
    public List<Region> regions;
    public boolean isAscii;

    public FileSource(Path path, List<Region> regions, boolean isAscii) throws IOException {
      this.path = path;
      if (!path.toFile().exists()) {
        String msg = String.format(Locale.US, "File at %s does not exist, skipping.", path);
        logger.warn(msg);
        throw new FileNotFoundException(msg);
      }
      if (path.toFile().length() == 0) {
        String msg = String.format(Locale.US, "File at %s is empty, skipping.", path);
        logger.warn(msg);
        throw new IOException(msg);
      }
      this.regions = regions;
      this.isAscii = isAscii;
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

    @Override
    public String toString() {
      return "Region{" + start + ":" + end + "}";
    }
  }

  static final Pattern POINTER_PAT =
      Pattern.compile("^(?<path>.+?)(?<isAscii>\\{ascii})?(?:\\[(?<regions>[0-9:,]+)])?$");

  public final List<FileSource> sources;

  public static boolean isPointer(String pointer) {
    if (pointer.startsWith("<")) {
      return false;
    }
    return Arrays.stream(pointer.split("\\+")).allMatch(SourcePointer::fitsPattern);
  }

  public static SourcePointer parse(String pointer) {
    if (!isPointer(pointer)) {
      throw new RuntimeException("Could not parse pointer: " + pointer);
    }
    String[] sourceTokens = pointer.split("\\+");
    List<FileSource> fileSources =
        Arrays.stream(sourceTokens).map(SourcePointer::toFileSource).collect(Collectors.toList());
    if (fileSources.isEmpty()) {
      return null;
    } else {
      return new SourcePointer(fileSources);
    }
  }

  static boolean fitsPattern(String pointerToken) {
    Matcher matcher = POINTER_PAT.matcher(pointerToken);
    if (!matcher.matches()) {
      logger.error("No match from pointer '{}' for '{}'!", pointerToken, POINTER_PAT);
      return false;
    }
    return true;
  }

  static FileSource toFileSource(String pointerToken) {
    Matcher m = POINTER_PAT.matcher(pointerToken);
    if (!m.find()) {
      logger.error("Pointer '{}' not matching pattern '{}'!", pointerToken, POINTER_PAT);
      throw new RuntimeException("Could not parse source pointer from '" + pointerToken + ".");
    }
    Path sourcePath = Paths.get(m.group("path"));
    List<SourcePointer.Region> regions = ImmutableList.of();
    if (m.group("regions") != null) {
      regions =
          Arrays.stream(m.group("regions").split(","))
              .map(SourcePointer::parseRegion)
              .sorted(Comparator.comparingInt(r -> r.start))
              .collect(Collectors.toList());
    }
    try {
      return new SourcePointer.FileSource(sourcePath, regions, m.group("isAscii") != null);
    } catch (FileNotFoundException e) {
      logger.error("SourcePath '{}' not existing!", sourcePath);
      throw new RuntimeException("Could not locate file at '" + sourcePath + ".");
    } catch (IOException e) {
      logger.error("SourcePath '{}' unreadable!", sourcePath);
      throw new RuntimeException("Could not read file at '" + sourcePath + ".");
    }
  }

  static SourcePointer.Region parseRegion(String r) {
    if (r.startsWith(":")) {
      return new SourcePointer.Region(0, Integer.parseInt(r.substring(1)));
    } else if (r.endsWith(":")) {
      return new SourcePointer.Region(Integer.parseInt(r.substring(0, r.length() - 1)), -1);
    } else {
      String[] offsets = r.split(":");
      return new SourcePointer.Region(Integer.parseInt(offsets[0]), Integer.parseInt(offsets[1]));
    }
  }

  public SourcePointer(List<FileSource> sources) {
    this.sources = sources;
  }

  /**
   * Create meanigful human-readable representation of {@link SourcePointer} from it's attached
   * files
   */
  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    for (FileSource source : this.sources) {
      if (sb.length() > 0) {
        sb.append("+");
      }
      sb.append(source.path);
      if (source.isAscii) {
        sb.append("{ascii}");
      }
      if (!source.regions.isEmpty()) {
        sb.append("[");
        for (Region region : source.regions) {
          if (sb.charAt(sb.length() - 1) != '[') {
            sb.append(",");
          }
          sb.append(region.start);
          sb.append(":");
          sb.append(region.end);
        }
        sb.append("]");
      }
    }
    return sb.toString();
  }
}
