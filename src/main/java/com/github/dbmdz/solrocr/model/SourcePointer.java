package com.github.dbmdz.solrocr.model;

import com.github.dbmdz.solrocr.reader.*;
import io.minio.MinioClient;
import java.io.*;
import java.lang.invoke.MethodHandles;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
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
    S3
  }

  public static class Source {

    public final SourceType type;
    public final String target;
    public List<Region> regions;
    public boolean isAscii;

    public Source(String target, List<Region> regions, boolean isAscii) throws IOException {
      this.type = determineType(target);
      this.target = target;
      this.regions = regions;
      this.isAscii = isAscii;
    }

    static SourceType determineType(String target) throws IOException {
      if (target.startsWith("/")) {
        return SourceType.FILESYSTEM;
      } else if (Files.exists(Paths.get(target))) {
        return SourceType.FILESYSTEM;
      } else if (target.startsWith("s3://")) {
        return SourceType.S3;
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
      List<Region> regions = new ArrayList<>();
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

    public SourceReader getReader(int sectionSize, int maxCacheEntries, MinioClient s3Client)
        throws IOException {
      if (this.type == SourceType.FILESYSTEM) {
        return new FileSourceReader(
            Paths.get(this.target), SourcePointer.parse(this.target), sectionSize, maxCacheEntries);
      } else if (this.type == SourceType.S3) {
        return new S3ObjectSourceReader(
            s3Client,
            URI.create(this.target),
            SourcePointer.parse(this.target),
            sectionSize,
            maxCacheEntries);
      } else {
        throw new UnsupportedOperationException("Unsupported source type '" + this.type + "'.");
      }
    }
  }

  public static class Region {

    public int start;
    public int end;

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
  public SourceReader getReader(int sectionSize, int maxCacheEntries, MinioClient s3client)
      throws IOException {
    if (this.sources.size() == 1) {
      if (this.sources.get(0).type == SourceType.FILESYSTEM) {
        return new FileSourceReader(
            Paths.get(this.sources.get(0).target), this, sectionSize, maxCacheEntries);
      } else if (this.sources.get(0).type == SourceType.S3) {
        return new S3ObjectSourceReader(
            s3client, URI.create(this.sources.get(0).target), this, sectionSize, maxCacheEntries);
      } else {
        throw new IOException(
            String.format(Locale.US, "Pointer %s contains an unsupported target type.", this));
      }
    } else {
      return new MultiSourceReader(this, sectionSize, maxCacheEntries, s3client);
    }
  }
}
