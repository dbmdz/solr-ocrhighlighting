package org.mdz.search.solrocr.lucene.fieldloader;

import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.SolrException.ErrorCode;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.core.PluginInfo;
import org.apache.solr.util.plugin.PluginInfoInitialized;
import org.mdz.search.solrocr.util.FileBytesCharIterator;
import org.mdz.search.solrocr.util.FileCharIterator;
import org.mdz.search.solrocr.util.IterableCharSequence;

/** Load field values from filesystem paths.
 *
 * Must be configured with a mapping of field names to path patterns. In the pattern, <pre>{docId}</pre> is replaced
 * with the <pre>"id"</pre> field of the document that the field content is to be retrieved for.
 *
 * The files the resolver loads from <strong>must be encoded in UTF-16, all other encodings are not supported.</strong>
 * It is recommended to include a Byte-Order marker in the files, although the loader contains some heuristics to
 * detect the endianness without its presence.
 *
 * Example:
 *
 * <pre>
 * {@code
 *  <fieldLoader class="org.mdz.search.solrocr.lucene.fieldloader.PathFieldLoader">
 *    <lst name="externalFields">
 *      <str name="ocr_text">/opt/miniocr/content/bsb_content{docId[7-10]}/{docId[0-10]}/xml/standard/2.2/{docId[0-10]}_miniocr.xml</str>
 *    </lst>
 *  </fieldLoader>
 *  }
 * </pre>
 *
 * */
public class PathFieldLoader implements ExternalFieldLoader, PluginInfoInitialized {
  // `{fieldName}`, `{fieldName[:-3]`
  private static final Pattern variablePat = Pattern.compile(
      "\\{(?<fieldName>.+?)(?:\\[(?<startIdx>-?\\d+)?(?:(?<rangeChar>:)(?<endIdx>-?\\d+)?)?])?}");
  private static final Set<Charset> supportedCharsets = ImmutableSet.of(
      StandardCharsets.US_ASCII,
      StandardCharsets.UTF_8,
      StandardCharsets.UTF_16,
      StandardCharsets.UTF_16LE,
      StandardCharsets.UTF_16BE);

  private Map<String, String> fieldPatterns;
  private Set<String> requiredFieldValues;
  private Charset charset;

  @Override
  public void init(PluginInfo info) {
    String cset = info.attributes.get("encoding");
    if (cset == null) {
      cset = "utf-16";
    }
    this.charset = Charset.forName(cset);
    if (!supportedCharsets.contains(this.charset)) {
      throw new SolrException(
          ErrorCode.FORBIDDEN,
          String.format("Invalid encoding '%s', must be one of 'ascii', 'utf-8', 'utf-16', 'utf-16le' or 'utf-16be'",
                        cset));
    }
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
    }
    return interpolated.toString();
  }

  @Override
  public IterableCharSequence loadField(Map<String, String> fields, String fieldName) throws IOException {
    String fieldPattern = fieldPatterns.get(fieldName);
    Path p = Paths.get(interpolateVariables(fieldPattern, fields));
    try {
      if (this.charset == StandardCharsets.UTF_8 || this.charset == StandardCharsets.US_ASCII) {
        return new FileBytesCharIterator(p, this.charset);
      } else {
        return new FileCharIterator(p);
      }
    } catch (NoSuchFileException e) {
      return null;
    }
  }
}
