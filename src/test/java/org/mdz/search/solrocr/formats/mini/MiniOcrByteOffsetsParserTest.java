package org.mdz.search.solrocr.formats.mini;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


class MiniOcrByteOffsetsParserTest {
  private static final Pattern OFFSET_PAT = Pattern.compile("\\s(.+?)⚑(\\d+)");

  @Test
  void testParse() throws IOException {
    Path hocrPath = Paths.get("src/test/resources/data/miniocr_utf8.xml");
    FileChannel chan = FileChannel.open(hocrPath, StandardOpenOption.READ);
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    MiniOcrByteOffsetsParser.parse(Files.readAllBytes(hocrPath), bos);
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
}