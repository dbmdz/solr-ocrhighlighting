package org.mdz.search.solrocr.formats.alto;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.mdz.search.solrocr.util.IterableCharSequence;
import org.mdz.search.solrocr.util.MultiFileBytesCharIterator;

import static org.assertj.core.api.Assertions.assertThat;


class AltoByteOffsetsParserTest {
  private static final Pattern OFFSET_PAT = Pattern.compile("\\s(.+?)⚑(\\d+)");

  @Test
  void testParse() throws IOException {
    Path altoPath = Paths.get("src/test/resources/data/alto_multi_utf8/1860-11-30_01-00001.xml");
    FileChannel chan = FileChannel.open(altoPath, StandardOpenOption.READ);
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    AltoByteOffsetsParser.parse(Files.readAllBytes(altoPath), bos);
    String text = bos.toString(StandardCharsets.UTF_8.toString());
    Matcher m = OFFSET_PAT.matcher(text);
    while (m.find()) {
      String word = m.group(1);
      String offsetStr = m.group(2);
      int offset = Integer.parseInt(offsetStr);
      ByteBuffer buf = ByteBuffer.allocate(word.getBytes(StandardCharsets.UTF_8).length);
      chan.position(offset);
      chan.read(buf);
      buf.flip();
      String wordDecoded = new String(buf.array(), StandardCharsets.UTF_8);
      assertThat(wordDecoded).isEqualTo(word);
    }
  }

  @Test
  void testParseCombined() throws IOException {
    Path basePath = Paths.get("src/test/resources/data/alto_multi_utf8");
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    File[] ocrFiles = basePath.toFile().listFiles(pathname -> pathname.getName().endsWith(".xml"));
    Arrays.sort(ocrFiles);
    for (int i=0; i < ocrFiles.length; i++) {
      bos.write(Files.readAllBytes(ocrFiles[i].toPath()));
    }
    byte[] ocrData = bos.toByteArray();
    bos.reset();

    AltoByteOffsetsParser.parse(ocrData, bos);
    String text = bos.toString(StandardCharsets.UTF_8.toString());
    Matcher m = OFFSET_PAT.matcher(text);
    IterableCharSequence seq = new MultiFileBytesCharIterator(
        Arrays.stream(ocrFiles).map(File::toPath).collect(Collectors.toList()),
        StandardCharsets.UTF_8);
    while (m.find()) {
      String word = m.group(1);
      String offsetStr = m.group(2);
      int offset = Integer.parseInt(offsetStr);
      String wordDecoded = seq.subSequence(offset, offset + word.getBytes(StandardCharsets.UTF_8).length).toString();
      assertThat(wordDecoded).isEqualTo(word);
    }
  }
}