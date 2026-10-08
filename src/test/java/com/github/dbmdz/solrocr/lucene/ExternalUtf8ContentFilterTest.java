package com.github.dbmdz.solrocr.lucene;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.dbmdz.solrocr.lucene.filters.ByteSeekableReader;
import com.github.dbmdz.solrocr.lucene.filters.ExternalUtf8ContentFilter;
import com.github.dbmdz.solrocr.model.SourcePointer.Region;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.lucene.analysis.CharFilter;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import solrocr.ExternalUtf8ContentFilterFactory;

public class ExternalUtf8ContentFilterTest {
  private ExternalUtf8ContentFilterFactory fac;

  @TempDir Path tempDir;

  @BeforeEach
  public void setup() {
    fac = new ExternalUtf8ContentFilterFactory(new HashMap<>());
  }

  @Test
  public void testDecodedLengthCalculation() {
    String randomUnicode =
        "aaaaaaaaaaaaaaa"
            + "\uDB6C\uDE16+䝤즐˿혂\u139A\uDA4F\uDFAA`ҧĪ\u05EF\uDB7E\uDF45|\uDBA6\uDCB0륱7幰S5\uDAFD\uDF22뭗b"
            + "晓㈗I{䯞ʆ\uDA19\uDDACl씤\n"
            + "&0,\uDA19\uDF16ۦ\uDB03\uDC3EU탍蚳ԏ\uDBF2\uDECE҈\uD9EC\uDC14Q3փ\uD9E6\uDCEE\uD8CA\uDD09"
            + "\uDBEB\uDF31<ۥ⥘Ⱉ㢗Ѡ\uD921\uDEE8\uDA77\uDEF6㿸\uDAAF\uDCCB\uDA3D\uDF42ˡР\n"
            + "\uD934\uDDD6+ɖ\uD84D\uDFE9삶2\uDA25\uDD38͖ķ索\uDA43\uDF5Cڭp\uD834\uDE79\uDB5F\uDD56"
            + "ش\uD963\uDD0D㟢;\u09D0\uDAB0\uDF08x֗\uEEB4ȹ肇ǘ띻\uD925\uDFEFߥ۽\u0094\n"
            + "W\uDAE4\uDF92\uD8F4\uDC1Aڕ\uDBCC\uDF96\uD970\uDDEC\"翶Ļ\uDB75\uDFF2\uD80E\uDCAB\uDAA5"
            + "\uDF0BԼ\uDAC2\uDFE8ѥ\u0AFCi!<ʘ�肾\uDBA3\uDE9AʒኲQ䧲ˢZ膂\uDB8C\uDF01ǘ\n"
            + "z\uF485մټҘ»\uDB02\uDD15Ȧ\uDBAD\uDE69ԕа뿇1ꈂ\uD9A9\uDDEC\uD9E4\uDC11\uDA47\uDD1A᳦誳\uD8A9"
            + "\uDCF8k8ㅋݏ\uDB74\uDEE1杽ܷ\uDAA1\uDC4ELꢾ냃H";
    byte[] utf8Bytes = randomUnicode.getBytes(StandardCharsets.UTF_8);
    Assertions.assertThat(new String(utf8Bytes, StandardCharsets.UTF_8))
        .hasSize(randomUnicode.length());
  }

  @Test
  public void extractFully() throws IOException {
    Path p = Paths.get("src/test/resources/data/hocr.html");
    CharFilter filter =
        new ExternalUtf8ContentFilter(
            Files.newByteChannel(p),
            ImmutableList.of(new Region(0, (int) p.toFile().length())),
            p.toString());
    String full = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    String filtered = IOUtils.toString(filter);
    assertThat(filtered).isNotEmpty();
    assertThat(filter.correctOffset(full.indexOf("id='page_108'"))).isEqualTo(3033402);
    assertThat(filter.correctOffset(full.indexOf("Sonnenw"))).isEqualTo(3035988);
    assertThat(filter.correctOffset(full.indexOf("id='word_108_252'"))).isEqualTo(3066258);
  }

  @Test
  public void extractRegion() throws IOException {
    Path p = Paths.get("src/test/resources/data/hocr.html");
    String fieldValue = p.toString() + "[3033216:3066308]";
    byte[] fileBytes = Files.readAllBytes(p);
    String subRegion =
        new String(ArrayUtils.subarray(fileBytes, 3033216, 3066308), StandardCharsets.UTF_8);
    ExternalUtf8ContentFilter filter =
        (ExternalUtf8ContentFilter) fac.create(new StringReader(fieldValue));
    String filtered = IOUtils.toString(filter);
    assertThat(filtered).isEqualTo(subRegion);
    assertThat(filter.correctOffset(subRegion.indexOf("id='page_108'"))).isEqualTo(3033402);
    assertThat(filter.correctOffset(subRegion.indexOf("Sonnenw"))).isEqualTo(3035988);
    assertThat(filter.correctOffset(subRegion.indexOf("id='word_108_252'"))).isEqualTo(3066258);
    assertThat(filtered).contains("<div class='ocr_page' id='page_108'");
    assertThat(filtered).doesNotContain("<div class='ocr_page' id='page_109'");
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 2, 3, 7, 8192})
  void truncatedCharactersKeepTheirSourceOffsets(int chunkSize) throws IOException {
    for (String character : Arrays.asList("é", "€", "😀")) {
      byte[] bytes = ("ABC" + character + "DEF").getBytes(StandardCharsets.UTF_8);
      Path path = tempDir.resolve("boundary.txt");
      Files.write(path, bytes);
      int characterBytes = character.getBytes(StandardCharsets.UTF_8).length;
      for (int prefixBytes = 1; prefixBytes < characterBytes; prefixBytes++) {
        int end = 3 + prefixBytes;
        try (ExternalUtf8ContentFilter filter =
            new ExternalUtf8ContentFilter(
                Files.newByteChannel(path),
                ImmutableList.of(new Region(0, end)),
                path.toString())) {
          assertThat(readInChunks(filter, chunkSize)).isEqualTo("ABC\uFFFD");
          assertThat(filter.correctOffset(3)).isEqualTo(3);
          assertThat(filter.correctOffset(4)).isEqualTo(end);
        }
        try (ExternalUtf8ContentFilter filter =
            new ExternalUtf8ContentFilter(
                Files.newByteChannel(path),
                ImmutableList.of(new Region(0, end), new Region(3 + characterBytes, bytes.length)),
                path.toString())) {
          assertThat(readInChunks(filter, chunkSize)).isEqualTo("ABC\uFFFDDEF");
          assertThat(filter.correctOffset(4)).isEqualTo(3 + characterBytes);
          assertThat(filter.correctOffset(7)).isEqualTo(bytes.length);
        }
      }
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 2, 3, 7, 8192})
  void supplementaryCharactersAndLiteralReplacementKeepTheirOffsets(int chunkSize)
      throws IOException {
    String text = "A😀é\uFFFDZ";
    Path path = tempDir.resolve("unicode.txt");
    Files.write(path, text.getBytes(StandardCharsets.UTF_8));
    try (ExternalUtf8ContentFilter filter =
        new ExternalUtf8ContentFilter(Files.newByteChannel(path), null, path.toString())) {
      assertThat(readInChunks(filter, chunkSize)).isEqualTo(text);
      int[] offsets = {0, 1, 1, 5, 7, 10, 11};
      for (int i = 0; i < offsets.length; i++) {
        assertThat(filter.correctOffset(i)).isEqualTo(offsets[i]);
      }
      assertThat(filter.read(new char[1], 0, 0)).isZero();
    }
  }

  @Test
  void charactersAcrossInternalBufferBoundaries() throws IOException {
    for (int prefixLength : new int[] {8191, 8192, 16383, 16384}) {
      String prefix = String.join("", Collections.nCopies(prefixLength, "a"));
      String text = prefix + "😀Z";
      Path path = tempDir.resolve("buffer-boundary.txt");
      Files.write(path, text.getBytes(StandardCharsets.UTF_8));
      for (int suffixBytes = 1; suffixBytes <= 5; suffixBytes++) {
        int end = prefixLength + suffixBytes;
        try (ExternalUtf8ContentFilter filter =
            new ExternalUtf8ContentFilter(
                Files.newByteChannel(path),
                ImmutableList.of(new Region(0, end)),
                path.toString())) {
          String expected = prefix + (suffixBytes < 4 ? "\uFFFD" : suffixBytes == 4 ? "😀" : "😀Z");
          assertThat(readInChunks(filter, 8192)).isEqualTo(expected);
          assertThat(filter.correctOffset(expected.length())).isEqualTo(end);
        }
      }
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 2, 3, 31, 128, 8192})
  void mixedUnicodeAndAsciiRunsKeepEveryOffset(int chunkSize) throws IOException {
    Random random = new Random(42);
    StringBuilder text = new StringBuilder();
    List<Integer> offsets = new ArrayList<>();
    offsets.add(0);
    int byteOffset = 0;
    int[] unicode = {0xE9, 0x20AC, 0xFFFD, 0x4E2D, 0x1F600, 0x10FFFF};
    for (int block = 0; block < 300; block++) {
      for (int ascii = random.nextInt(100); ascii > 0; ascii--) {
        text.append((char) (32 + random.nextInt(95)));
        offsets.add(++byteOffset);
      }
      int codePoint = unicode[random.nextInt(unicode.length)];
      String character = new String(Character.toChars(codePoint));
      text.append(character);
      if (character.length() == 2) {
        offsets.add(byteOffset); // Both halves point to the same source code point.
      }
      byteOffset += character.getBytes(StandardCharsets.UTF_8).length;
      offsets.add(byteOffset);
    }
    Path path = tempDir.resolve("mixed.txt");
    Files.write(path, text.toString().getBytes(StandardCharsets.UTF_8));
    try (ExternalUtf8ContentFilter filter =
        new ExternalUtf8ContentFilter(Files.newByteChannel(path), null, path.toString())) {
      assertThat(readInChunks(filter, chunkSize)).isEqualTo(text.toString());
      for (int i = 0; i < offsets.size(); i++) {
        assertThat(filter.correctOffset(i))
            .as("offset %s, chunk %s", i, chunkSize)
            .isEqualTo(offsets.get(i));
      }
    }
  }

  @Test
  void changingReadBuffersPreservesSurrogatesAndReplacementWidths() throws IOException {
    Path path = tempDir.resolve("changing-buffers.txt");
    Files.write(path, "AB😀é€Z".getBytes(StandardCharsets.UTF_8));
    try (ExternalUtf8ContentFilter filter =
        new ExternalUtf8ContentFilter(
            Files.newByteChannel(path),
            ImmutableList.of(new Region(0, 10), new Region(11, 12)),
            path.toString())) {
      StringBuilder text = new StringBuilder();
      for (int size : new int[] {1, 2, 3, 1}) {
        char[] buffer = new char[size + 2];
        int read = filter.read(buffer, 1, size);
        assertThat(read).isEqualTo(size);
        text.append(buffer, 1, read);
      }
      assertThat(text.toString()).isEqualTo("AB😀é\uFFFDZ");
      int[] offsets = {0, 1, 2, 2, 6, 8, 11, 12};
      for (int i = 0; i < offsets.length; i++) {
        assertThat(filter.correctOffset(i)).isEqualTo(offsets[i]);
      }
      assertThat(filter.read(new char[1], 0, 1)).isEqualTo(-1);
    }
  }

  @Test
  void repositionDiscardsBufferedSurrogateAndReplacement() throws IOException {
    Path path = tempDir.resolve("reposition.txt");
    Files.write(path, "😀Zé".getBytes(StandardCharsets.UTF_8));
    try (ByteSeekableReader reader = new ByteSeekableReader(Files.newByteChannel(path))) {
      char[] buffer = new char[1];
      assertThat(reader.read(buffer, 0, 1)).isEqualTo(1);
      assertThat(Character.isHighSurrogate(buffer[0])).isTrue();
      reader.position(4, 6); // Z and the first byte of é.
      assertThat(reader.read(buffer, 0, 1)).isEqualTo(1);
      assertThat(buffer[0]).isEqualTo('Z');
      reader.position(4, 5); // Discard the buffered boundary replacement as well.
      assertThat(reader.read(buffer, 0, 1)).isEqualTo(1);
      assertThat(buffer[0]).isEqualTo('Z');
      assertThat(reader.read(buffer, 0, 1)).isEqualTo(-1);
    }
  }

  @Test
  void decoderDoesNotInspectBytesOutsideTheRegion() throws IOException {
    Path path = tempDir.resolve("bounded.txt");
    Files.write(path, new byte[] {65, 66, 67, (byte) 0xFF});
    try (ExternalUtf8ContentFilter filter =
        new ExternalUtf8ContentFilter(
            Files.newByteChannel(path), ImmutableList.of(new Region(0, 3)), path.toString())) {
      assertThat(IOUtils.toString(filter)).isEqualTo("ABC");
    }
  }

  @Test
  void malformedInputStillFailsInsteadOfDroppingValidText() throws IOException {
    Path path = tempDir.resolve("malformed.txt");
    for (byte[] bytes : new byte[][] {{65, (byte) 0xFF, 66, 67}, {65, (byte) 0xE0, (byte) 0x80}}) {
      Files.write(path, bytes);
      try (ExternalUtf8ContentFilter filter =
          new ExternalUtf8ContentFilter(Files.newByteChannel(path), null, path.toString())) {
        assertThatThrownBy(() -> IOUtils.toString(filter))
            .isInstanceOf(IOException.class)
            .hasCauseInstanceOf(MalformedInputException.class);
      }
    }
    Files.write(path, "éZ".getBytes(StandardCharsets.UTF_8));
    try (ExternalUtf8ContentFilter filter =
        new ExternalUtf8ContentFilter(
            Files.newByteChannel(path), ImmutableList.of(new Region(1, 3)), path.toString())) {
      assertThatThrownBy(() -> IOUtils.toString(filter))
          .isInstanceOf(IOException.class)
          .hasMessage("Failed to read region 1:3 from '%s' near byte offset 1", path)
          .hasCauseInstanceOf(MalformedInputException.class);
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 2, 3, 7, 8192})
  void truncatedSurrogateEncodingsAreRejected(int chunkSize) throws IOException {
    Path path = tempDir.resolve("truncated-surrogate.txt");
    // ED A0..BF cannot begin a valid scalar value, even without a third byte.
    for (int secondByte = 0xA0; secondByte <= 0xBF; secondByte++) {
      for (boolean bounded : new boolean[] {false, true}) {
        byte[] bytes =
            bounded
                ? new byte[] {65, (byte) 0xED, (byte) secondByte, (byte) 0x80, 90}
                : new byte[] {65, (byte) 0xED, (byte) secondByte};
        Files.write(path, bytes);
        try (ExternalUtf8ContentFilter filter =
            new ExternalUtf8ContentFilter(
                Files.newByteChannel(path),
                bounded ? ImmutableList.of(new Region(0, 3)) : null,
                path.toString())) {
          assertThatThrownBy(() -> readInChunks(filter, chunkSize))
              .as("second byte %s, bounded %s", secondByte, bounded)
              .isInstanceOf(IOException.class)
              .hasCauseInstanceOf(MalformedInputException.class);
        }
      }
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 2, 3, 7, 8192})
  void truncatedNonSurrogatePrefixesStillKeepTheirSourceOffsets(int chunkSize) throws IOException {
    Path path = tempDir.resolve("truncated-non-surrogate.txt");
    for (int secondByte = 0x80; secondByte <= 0x9F; secondByte++) {
      Files.write(path, new byte[] {65, (byte) 0xED, (byte) secondByte, (byte) 0xBF});
      try (ExternalUtf8ContentFilter filter =
          new ExternalUtf8ContentFilter(
              Files.newByteChannel(path), ImmutableList.of(new Region(0, 3)), path.toString())) {
        assertThat(readInChunks(filter, chunkSize)).isEqualTo("A\uFFFD");
        assertThat(filter.correctOffset(1)).isEqualTo(1);
        assertThat(filter.correctOffset(2)).isEqualTo(3);
      }
    }
  }

  @Test
  void regionEndBeyondTheFileEndDecodesTheRemainder() throws IOException {
    Path path = tempDir.resolve("short-file.txt");
    Files.write(path, "hello".getBytes(StandardCharsets.UTF_8));
    try (ExternalUtf8ContentFilter filter =
        new ExternalUtf8ContentFilter(
            Files.newByteChannel(path), ImmutableList.of(new Region(2, 1000)), path.toString())) {
      assertThat(IOUtils.toString(filter)).isEqualTo("llo");
    }
  }

  @Test
  void emptyRegionAtTheFileEndDecodesNothing() throws IOException {
    Path path = tempDir.resolve("empty-region.txt");
    Files.write(path, "hello".getBytes(StandardCharsets.UTF_8));
    try (ExternalUtf8ContentFilter filter =
        new ExternalUtf8ContentFilter(
            Files.newByteChannel(path), ImmutableList.of(new Region(5, 5)), path.toString())) {
      assertThat(filter.read(new char[4], 0, 4)).isEqualTo(-1);
    }
  }

  @ParameterizedTest
  @CsvSource({"0,-1", "3,2", "-1,2"})
  void unresolvableRegionsAreRejected(int start, int end) throws IOException {
    Path path = tempDir.resolve("invalid-region.txt");
    Files.write(path, "hello".getBytes(StandardCharsets.UTF_8));
    assertThatThrownBy(
            () ->
                new ExternalUtf8ContentFilter(
                    Files.newByteChannel(path),
                    ImmutableList.of(new Region(start, end)),
                    path.toString()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(path.toString());
  }

  @Test
  void openEndedRegionsAreResolvedByTheFactory() throws IOException {
    Path path = tempDir.resolve("open-ended.txt");
    Files.write(path, "hello".getBytes(StandardCharsets.UTF_8));
    ExternalUtf8ContentFilter filter =
        (ExternalUtf8ContentFilter) fac.create(new StringReader(path + "[2:]"));
    assertThat(IOUtils.toString(filter)).isEqualTo("llo");
    assertThat(filter.correctOffset(3)).isEqualTo(5);
  }

  @Test
  void zeroByteChannelReadsFailInsteadOfSpinning() throws IOException {
    Path path = tempDir.resolve("zero-byte-reads.txt");
    Files.write(path, "hello".getBytes(StandardCharsets.UTF_8));
    try (ExternalUtf8ContentFilter filter =
        new ExternalUtf8ContentFilter(
            new ZeroByteChannel(Files.newByteChannel(path)), null, path.toString())) {
      assertThatThrownBy(() -> IOUtils.toString(filter))
          .isInstanceOf(IOException.class)
          .hasMessage("Failed to read region 0:5 from '%s' near byte offset 0", path)
          .hasCauseExactlyInstanceOf(IOException.class)
          .cause()
          .hasMessage("Underlying byte channel returned zero bytes");
    }
  }

  /** Reports every read as zero bytes, like a channel that never has data ready. */
  private static class ZeroByteChannel implements SeekableByteChannel {
    private final SeekableByteChannel delegate;

    ZeroByteChannel(SeekableByteChannel delegate) {
      this.delegate = delegate;
    }

    @Override
    public int read(ByteBuffer dst) {
      return 0;
    }

    @Override
    public int write(ByteBuffer src) throws IOException {
      return delegate.write(src);
    }

    @Override
    public long position() throws IOException {
      return delegate.position();
    }

    @Override
    public SeekableByteChannel position(long newPosition) throws IOException {
      delegate.position(newPosition);
      return this;
    }

    @Override
    public long size() throws IOException {
      return delegate.size();
    }

    @Override
    public SeekableByteChannel truncate(long size) throws IOException {
      delegate.truncate(size);
      return this;
    }

    @Override
    public boolean isOpen() {
      return delegate.isOpen();
    }

    @Override
    public void close() throws IOException {
      delegate.close();
    }
  }

  private static String readInChunks(ExternalUtf8ContentFilter filter, int chunkSize)
      throws IOException {
    // Use a nonzero buffer offset and stale surrogate data outside the requested slice.
    char[] buffer = new char[chunkSize + 2];
    Arrays.fill(buffer, '\uD83D');
    StringBuilder text = new StringBuilder();
    int count;
    while ((count = filter.read(buffer, 1, chunkSize)) != -1) {
      assertThat(count).isPositive();
      text.append(buffer, 1, count);
    }
    assertThat(filter.read(buffer, 1, chunkSize)).isEqualTo(-1);
    return text.toString();
  }

  @Test
  public void multipleFiles() throws IOException {
    Path basePath = Paths.get("src/test/resources/data/multi_txt");
    String ptr =
        Files.list(basePath)
            .filter(p -> p.getFileName().toString().startsWith("part_"))
            .map(p -> p.toAbsolutePath().toString())
            .sorted()
            .collect(Collectors.joining("+"));
    ExternalUtf8ContentFilter filter =
        (ExternalUtf8ContentFilter) fac.create(new StringReader(ptr));
    String filtered = IOUtils.toString(filter);
    assertThat(filtered)
        .isEqualTo("ene mene mistäes rappelt in der kistäene mene mäckund du bist wäg");
    assertThat(filter.correctOffset(filtered.indexOf("mistäes"))).isEqualTo(9);
    assertThat(filter.correctOffset(filtered.indexOf("kistäene"))).isEqualTo(33);
    assertThat(filter.correctOffset(filtered.indexOf("wäg"))).isEqualTo(65);
  }

  @Test
  public void multipleFilesWithRegions() throws IOException {
    String ptr =
        "src/test/resources/data/multi_txt/part_1.txt[9:]"
            + "+src/test/resources/data/multi_txt/part_2.txt[3:10]"
            + "+src/test/resources/data/multi_txt/part_3.txt[:8]"
            + "+src/test/resources/data/multi_txt/part_4.txt[4:6,12:]";
    ExternalUtf8ContentFilter filter =
        (ExternalUtf8ContentFilter) fac.create(new StringReader(ptr));
    String filtered = IOUtils.toString(filter);
    assertThat(filtered).isEqualTo("mistärappeltene meneduwäg");
    assertThat(filter.correctOffset(filtered.indexOf("rappelt"))).isEqualTo(18);
    assertThat(filter.correctOffset(filtered.indexOf("ene mene"))).isEqualTo(39);
    assertThat(filter.correctOffset(filtered.indexOf("du"))).isEqualTo(57);
    assertThat(filter.correctOffset(filtered.indexOf("wäg"))).isEqualTo(65);
  }

  @Test
  public void multipleRegions() throws IOException {
    String ptr = "src/test/resources/data/multi_txt/complete.txt[4:8,18:25,33:39]";
    ExternalUtf8ContentFilter filter =
        (ExternalUtf8ContentFilter) fac.create(new StringReader(ptr));
    String filtered = IOUtils.toString(filter);
    assertThat(filtered).isEqualTo("menerappeltkistä");
    assertThat(filter.correctOffset(4)).isEqualTo(18);
    assertThat(filter.correctOffset(11)).isEqualTo(33);
  }

  @Test
  public void multipleLongerFiles() throws IOException {
    Path aPath = Paths.get("src/test/resources/data/alto_multi/1865-05-24_01-00001.xml");
    Path bPath = Paths.get("src/test/resources/data/alto_multi/1865-05-24_01-00002.xml");
    String aText = new String(Files.readAllBytes(aPath), StandardCharsets.UTF_8);
    String bText = new String(Files.readAllBytes(bPath), StandardCharsets.UTF_8);
    String fullText = aText + bText;
    String fullPtr = aPath.toString() + "+" + bPath.toString();
    try (ExternalUtf8ContentFilter filter =
        (ExternalUtf8ContentFilter) fac.create(new StringReader(fullPtr))) {
      String filtered = IOUtils.toString(filter);
      assertThat(filtered.length()).isEqualTo(fullText.length());
      assertThat(filtered).isEqualTo(fullText);
    }
  }
}
