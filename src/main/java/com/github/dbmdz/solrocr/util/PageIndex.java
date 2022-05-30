package com.github.dbmdz.solrocr.util;

import static java.util.Objects.requireNonNull;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.common.math.IntMath;
import com.google.common.primitives.Bytes;
import java.awt.Dimension;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Read/Write OCR page index files.
 *
 * <p>These files record various metadata about the pages in a given multi-page OCR document and
 * allow the quick retrieval of these values without having to parse any markup.
 *
 * <p>The files are laid out as follows:
 *
 * <pre>
 * ┌────────────────┐
 * │     Header     │
 * │ 8 or 10 bytes  │
 * └────────────────┘
 * ╔════════════════╗
 * ║  Page Entries  ║
 * ║┌──────────────┐║
 * ║│     Entry    │║
 * ║│  4-16 bytes  │║
 * ║└──────────────┘║
 * ║       ...      ║
 * ╚════════════════╝
 * ┏╍╍╍╍╍╍╍╍╍╍╍╍╍╍╍╍┓
 * ┇  (optional)    ┇
 * ┇  Identifiers:  ┇
 * ┇  Sequence of   ┇
 * ┇  0-terminated  ┇
 * ┇  UTF8 strings  ┇
 * ┗╍╍╍╍╍╍╍╍╍╍╍╍╍╍╍╍┛
 * </pre>
 *
 * <strong>Header</strong>
 *
 * <ul>
 *   <li>Magic: <code>OCRIDX</code>
 *   <li>Version: <code>u8</code>
 *   <li>Features: <code>u8</code> bitset (see below)
 *   <li>Number of records: <code>u16</code>, only present when identifiers are encoded, otherwise
 *       determine from file size
 * </ul>
 *
 * <strong>Page Entry</strong>
 *
 * <ul>
 *   <li>Offset: <code>u32</code>, byte offset in the OCR file the page markup starts
 *   <li>Length (optional): <code>u32</code>, length of the OCR markup in the OCR file
 *   <li>ID Offset (optional): <code>u32</code>, offset in the <strong>index file</strong> at which
 *       the page's identifier is located.
 *   <li>Page Width (optional): <code>u16</code>
 *   <li>Page Height (optional): <code>u16</code>
 * </ul>
 *
 * As can be seen, the length and layout of page entries is highly variable and depends on the
 * features the index was created with, which can be determined from the <strong>feature
 * bitset</strong> in the header. It is laid out as follows (. means currently unassigned):
 *
 * <pre>
 * 0 0 0 0 0 0 0 0
 * . . . . . │ │ │
 *           │ │ └ Length of page markup
 *           │ └ Page identifiers
 *           └ Page dimensions (width and height)
 * </pre>
 *
 * If a feature results in additional values in the page entry, these values are added according to
 * their order in the feature bitset (or the above list), e.g. if both the page length and page
 * dimensions features are active (i.e. the bitset is <code>00000101</code>), the resulting page
 * entry will be a <code>(u32, u32, u16, u16)</code> structure.
 *
 * <p><strong>Note:</strong> The position of a page record in the file is determined by its <em>page
 * number</em>. If there are gaps in the OCR markup, e.g. if page #3 is followed by page #5 in the
 * markup, the index will have an all-zero entry for the missing fourth page. This allows for fast
 * retrieval of the page entry from the index given the (1-based) page number:
 *
 * <ul>
 *   <li>Read header (8 or 10 bytes) to determine record size
 *   <li>Seek to <code>headerSize + (pageNum - 1) * recordSize</li>
 *   <li>Read <code>recordSize</code> bytes</code>
 * </ul>
 */
public class PageIndex implements AutoCloseable {

  static final byte OFFSET_IDX_VERSION = 0x01;

  static final byte[] OFFSET_IDX_MAGIC = "OCRIDX".getBytes(StandardCharsets.UTF_8);

  private enum Feature {
    LENGTH,
    IDENTIFIER,
    DIMENSION
  }

  private static class Header {
    public final int version;
    public final Set<Feature> features;
    public final int headerSize;
    public final int numRecords;
    public final int recordSize;

    private static int getRecordSize(Set<Feature> features) {
      int recordSize = 4;
      if (features.contains(Feature.LENGTH)) {
        recordSize += 4;
      }
      if (features.contains(Feature.IDENTIFIER)) {
        recordSize += 4;
      }
      if (features.contains(Feature.DIMENSION)) {
        recordSize += 4;
      }
      return recordSize;
    }

    private static int getHeaderSize(Set<Feature> features) {
      int headerSize = 8;
      if (features.contains(Feature.IDENTIFIER)) {
        headerSize += 2;
      }
      return headerSize;
    }

    Header(int version, int numRecords, Set<Feature> features) {
      this.version = version;
      this.numRecords = numRecords;
      this.features = features;
      this.headerSize = getHeaderSize(features);
      this.recordSize = getRecordSize(features);
    }

    public byte[] compile() {
      byte featureVec = (byte) 0x00;
      if (features.contains(Feature.LENGTH)) {
        featureVec |= 0x01;
      }
      if (features.contains(Feature.IDENTIFIER)) {
        featureVec |= 0x02;
      }
      if (features.contains(Feature.DIMENSION)) {
        featureVec |= 0x04;
      }

      ByteBuffer buf = ByteBuffer.allocate(headerSize);
      buf.put(OFFSET_IDX_MAGIC);
      buf.put(OFFSET_IDX_VERSION);
      buf.put(featureVec);
      if (features.contains(Feature.IDENTIFIER)) {
        buf.putShort((short) numRecords);
      }
      return buf.array();
    }

    static Header parse(FileChannel chan) throws IOException {
      ByteBuffer magicBuf = ByteBuffer.allocate(6);
      chan.read(magicBuf);
      magicBuf.flip();
      if (!Arrays.equals(magicBuf.array(), OFFSET_IDX_MAGIC)) {
        throw new IOException("Invalid index file, expected OCRIDX magic at offset 0.");
      }
      ByteBuffer buf = ByteBuffer.allocate(2);
      chan.read(buf);
      buf.flip();
      Set<Feature> features = new HashSet<>();
      int numRecords = -1;
      byte version = buf.get(0);
      byte featureVec = buf.get(1);
      if ((featureVec & 0x01) != 0) {
        features.add(Feature.LENGTH);
      }
      if ((featureVec & 0x02) != 0) {
        features.add(Feature.IDENTIFIER);
        buf.position(8);
        numRecords = Short.toUnsignedInt(buf.getShort());
      }
      if ((featureVec & 0x04) != 0) {
        features.add(Feature.DIMENSION);
      }
      int headerSize = getHeaderSize(features);
      int recordSize = getRecordSize(features);
      if (numRecords < 0) {
        // No variable-length tail, just discard the header and count the bytes
        numRecords = (int) ((chan.size() - headerSize) / recordSize);
      }
      return new Header(version, numRecords, features);
    }
  }
  /**
   * This class represents a single page with an unique id, its position inside a byte stream and
   * its pixel dimensions.
   */
  public static class PageRecord {

    private final String id;
    private final long startOffset;
    private final long endOffset;
    private final Dimension dimensions;

    public PageRecord(String id, long startOffset, long endOffset, Dimension dimensions) {
      this.id = requireNonNull(id);
      this.startOffset = startOffset;
      this.endOffset = endOffset;
      this.dimensions = dimensions;
    }

    /** Get the page identifier */
    public String getId() {
      return id;
    }

    /** Get the starting byte offset of the page's markup structure in the source OCR file. */
    public long getStartOffset() {
      return startOffset;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      PageRecord pageRecord = (PageRecord) o;
      return startOffset == pageRecord.startOffset
          && endOffset == pageRecord.getEndOffset()
          && id.equals(pageRecord.id);
    }

    @Override
    public int hashCode() {
      return Objects.hash(id, startOffset, endOffset);
    }

    @Override
    public String toString() {
      return "Page{"
          + "id='"
          + id
          + '\''
          + ", startOffset="
          + startOffset
          + ", endOffset="
          + endOffset
          + ", dimensions="
          + (dimensions == null
              ? "null"
              : String.format(Locale.US, "%dx%d", dimensions.width, dimensions.height))
          + '}';
    }

    /**
     * Get the byte offset of the first character **after** the page's markup in the source OCR
     * file.
     */
    public long getEndOffset() {
      return endOffset;
    }

    /** Get the pixel dimensions of the page, can be null. */
    public Dimension getDimensions() {
      return dimensions;
    }
  }

  public static void writeIndex(List<PageRecord> pages, OutputStream os) throws IOException {
    int numPages = pages.size();
    boolean hasIdentifiers = pages.stream().map(PageRecord::getId).anyMatch(Objects::nonNull);
    boolean hasDimensions =
        pages.stream().map(PageRecord::getDimensions).anyMatch(Objects::nonNull);
    if (numPages < 1) {
      return;
    }

    Set<Feature> features = new HashSet<>(Collections.singletonList(Feature.LENGTH));
    if (hasIdentifiers) {
      features.add(Feature.IDENTIFIER);
    }
    if (hasDimensions) {
      features.add(Feature.DIMENSION);
    }
    Header header = new Header(0x01, numPages, features);
    ByteBuffer buf = ByteBuffer.allocate(header.headerSize + numPages * header.recordSize);
    ByteArrayOutputStream idOutput = null;
    int idOffset = buf.limit();
    if (hasIdentifiers) {
      idOutput = new ByteArrayOutputStream();
    }
    buf.put(header.compile());
    int idx = 0;
    for (PageRecord page : pages) {
      // Page numbers are 1-indexed, but we write to a 0-indexed file, hence - 1
      int outOff = header.headerSize + (idx * header.recordSize);
      // Offset
      buf.position(outOff);
      buf.putInt((int) page.getStartOffset());
      // Length
      buf.putInt((int) (page.getEndOffset() - page.getStartOffset()));
      if (hasIdentifiers) {
        if (page.getId() == null) {
          // No identifier, null pointer
          buf.putInt(0);
        } else {
          // Pointer to null-terminated UTF8 string in tail
          buf.putInt(idOffset);
          byte[] idBytes = page.getId().getBytes(StandardCharsets.UTF_8);
          idOutput.write(idBytes);
          idOutput.write(0x00);
          idOffset += (idBytes.length + 1);
        }
      }
      if (hasDimensions) {
        buf.putShort((short) page.getDimensions().width);
        buf.putShort((short) page.getDimensions().height);
      }
      idx += 1;
    }
    buf.rewind();
    os.write(buf.array());
    if (idOutput != null) {
      os.write(idOutput.toByteArray());
    }
  }

  public static void writeIndex(List<PageRecord> pages, Path targetPath) throws IOException {
    try (OutputStream os =
        Files.newOutputStream(targetPath, StandardOpenOption.WRITE, StandardOpenOption.CREATE)) {
      writeIndex(pages, os);
    }
  }

  private static final Set<Feature> REQUIRED_FEATURES =
      ImmutableSet.of(Feature.LENGTH, Feature.IDENTIFIER, Feature.DIMENSION);

  private final FileChannel chan;
  private final Header header;

  public PageIndex(Path idxPath) throws IOException {
    this.chan = FileChannel.open(idxPath, StandardOpenOption.READ);
    this.header = Header.parse(chan);
    if (!header.features.containsAll(REQUIRED_FEATURES)) {
      String missing =
          String.join(
              ", ",
              Sets.difference(REQUIRED_FEATURES, header.features).stream()
                  .map(Feature::toString)
                  .toArray(String[]::new));
      throw new IllegalArgumentException("Index file is missing required features: " + missing);
    }
  }

  public Optional<PageRecord> locatePage(int ocrOffset) throws IOException {
    if (header.numRecords == 0) {
      return Optional.empty();
    }
    boolean hasLength = header.features.contains(Feature.LENGTH);
    ByteBuffer buf = ByteBuffer.allocate(hasLength ? 8 : 4);
    // Use simple binary search to locate the page that contains the given offset
    int left = 0;
    int right = this.header.numRecords - 1;
    while (left < right) {
      int middle = IntMath.divide(left + right, 2, RoundingMode.FLOOR);
      chan.read(buf, header.headerSize + (long) middle * header.recordSize);
      buf.flip();
      long pageStart = Integer.toUnsignedLong(buf.getInt());
      if (ocrOffset < pageStart) {
        right = middle - 1;
        continue;
      }
      long pageEnd;
      if (hasLength) {
        pageEnd = pageStart + buf.getInt();
      } else {
        buf.rewind();
        chan.read(buf, middle + header.recordSize);
        buf.flip();
        pageEnd = buf.getInt();
      }
      if (ocrOffset >= pageEnd) {
        left = middle + 1;
      } else {
        return Optional.of(this.parsePage(pageStart));
      }
    }
    return Optional.empty();
  }

  private PageRecord parsePage(long pageOffset) throws IOException {
    if (pageOffset - header.headerSize % header.recordSize != 0) {
      throw new IllegalArgumentException(
          "Page offsets must be aligned to headerSize + recordSize!");
    }
    ByteBuffer buf = ByteBuffer.allocate(this.header.recordSize);
    chan.read(buf, pageOffset);
    buf.flip();
    long startOffset = Integer.toUnsignedLong(buf.getInt());
    long length = Integer.toUnsignedLong(buf.getInt());
    return new PageRecord(
        readIdentifier(Integer.toUnsignedLong(buf.getInt())),
        startOffset,
        startOffset + length,
        new Dimension(Short.toUnsignedInt(buf.getShort()), Short.toUnsignedInt(buf.getShort())));
  }

  private String readIdentifier(long offset) throws IOException {
    ByteBuffer buf = ByteBuffer.allocate(128);
    ByteArrayOutputStream bos = new ByteArrayOutputStream(128);
    do {
      int numRead = chan.read(buf, offset);
      if (numRead <= 0) {
        // EOF, just return what we read so far
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
      }
      buf.flip();
      int end = Bytes.indexOf(buf.array(), (byte) 0x00);
      if (end >= 0) {
        bos.write(buf.array());
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
      } else {
        bos.write(buf.array());
      }
      offset += numRead;
    } while (true);
  }

  @Override
  public void close() throws Exception {
    this.chan.close();
  }
}
