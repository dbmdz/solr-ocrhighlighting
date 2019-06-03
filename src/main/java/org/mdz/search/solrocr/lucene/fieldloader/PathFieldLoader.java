package org.mdz.search.solrocr.lucene.fieldloader;

import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.SolrException.ErrorCode;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.core.PluginInfo;
import org.apache.solr.util.plugin.PluginInfoInitialized;
import org.mdz.search.solrocr.util.FileBytesCharIterator;
import org.mdz.search.solrocr.util.IterableCharSequence;
import org.mdz.search.solrocr.util.MultiFileBytesCharIterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Load field values from filesystem paths. */
public class PathFieldLoader implements ExternalFieldLoader, PluginInfoInitialized {
  // `{fieldName}`, `{fieldName[:-3]`
  private static final Pattern variablePat = Pattern.compile(
      "\\{(?<fieldName>.+?)(?:\\[(?<startIdx>-?\\d+)?(?:(?<rangeChar>:)(?<endIdx>-?\\d+)?)?])?}");
  private static final Set<Charset> supportedCharsets = ImmutableSet.of(
      StandardCharsets.US_ASCII,
      StandardCharsets.UTF_8);
  private static final Logger logger = LoggerFactory.getLogger(PathFieldLoader.class);

  private Map<String, String> fieldPatterns;
  private Set<String> requiredFieldValues;
  private Charset charset;
  private boolean isMultiFile;

  @Override
  public void init(PluginInfo info) {
    String cset = info.attributes.get("encoding");
    if (cset == null) {
      throw new SolrException(
          ErrorCode.FORBIDDEN,
          "Missing 'encoding' attribute, must be one of 'ascii' or 'utf-8'");
    }
    this.charset = Charset.forName(cset);
    if (!supportedCharsets.contains(this.charset)) {
      throw new SolrException(
          ErrorCode.FORBIDDEN,
          String.format("Invalid encoding '%s', must be one of 'ascii' or 'utf-8'",  cset));
    }
    isMultiFile = info.attributes.getOrDefault("multiple", "false").equals("true");
    this.requiredFieldValues = new HashSet<>();
    this.fieldPatterns = new HashMap<>();
    NamedList<String> args = (NamedList<String>) info.initArgs.get("externalFields");
    args.forEach((fieldName, pattern) -> {
      Matcher m = variablePat.matcher(pattern);
      while (m.find()) {
        requiredFieldValues.add(m.group("fieldName"));
      }
      fieldPatterns.put(fieldName, pattern);
    });
  }

  @Override
  public boolean isExternalField(String fieldName) {
    return fieldPatterns.containsKey(fieldName);
  }

  @Override
  public Set<String> getRequiredFields() {
    return requiredFieldValues;
  }

  private String interpolateVariables(String pattern, Map<String, String> fieldValues) {
    StringBuilder interpolated = new StringBuilder(pattern);
    Matcher m = variablePat.matcher(pattern);
    while (m.find()) {
      String fieldName = m.group("fieldName");
      String fieldValue = fieldValues.get(fieldName);
      Integer startIdx = m.group("startIdx") != null ? Integer.parseInt(m.group("startIdx")) : null;
      if (startIdx != null && startIdx < 0) {
        startIdx = fieldValue.length() - startIdx;
      }
      Integer endIdx = m.group("endIdx") != null ? Integer.parseInt(m.group("endIdx")) : null;
      if (endIdx != null && endIdx < 0) {
        endIdx = fieldValue.length() - endIdx;
      }
      if (startIdx != null && endIdx != null && startIdx >= endIdx) {
        throw new IllegalArgumentException(String.format("Invalid range '%s' specified in pattern '%s'",
                                                         m.group(0), pattern));
      }
      boolean isRange = m.group("rangeChar") != null;

      if (startIdx != null && !isRange) {
        fieldValue = String.valueOf(fieldValue.charAt(startIdx));
      } else if (startIdx != null && endIdx == null) {
        fieldValue = fieldValue.substring(startIdx);
      } else if (startIdx == null && endIdx != null) {
        fieldValue = fieldValue.substring(0, endIdx);
      } else if (startIdx != null) {
        fieldValue = fieldValue.substring(startIdx, endIdx);
      }
      interpolated.replace(m.start(), m.end(), fieldValue);
      m = variablePat.matcher(interpolated);
    }
    return interpolated.toString();
  }

  @Override
  public IterableCharSequence loadField(Map<String, String> fields, String fieldName) throws IOException {
    String fieldPattern = fieldPatterns.get(fieldName);
    Path fPath = Paths.get(interpolateVariables(fieldPattern, fields));
    try {
      if (isMultiFile) {
        DirectoryStream.Filter<Path> filter = p -> !p.toFile().isDirectory();
        Path rootDir = fPath;
        if (fPath.getFileName().toString().contains("*")) {
          final PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + fPath.toString());
          filter = matcher::matches;
          rootDir = fPath.getParent();
        }
        List<Path> files = StreamSupport.stream(Files.newDirectoryStream(rootDir, filter).spliterator(), false)
            .sorted()
            .collect(Collectors.toList());
        return new MultiFileBytesCharIterator(files, charset);
      } else {
        return new FileBytesCharIterator(fPath, this.charset);
      }
    } catch (NoSuchFileException e) {
      throw new IOException(String.format("Could not find file at resolved path '%s'.", fPath), e);
    }
  }

  @Override
  public Charset getCharset() {
    return charset;
  }
}
