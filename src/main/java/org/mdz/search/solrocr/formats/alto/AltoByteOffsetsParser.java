package org.mdz.search.solrocr.formats.alto;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import net.byteseek.matcher.sequence.ByteSequenceMatcher;
import net.byteseek.matcher.sequence.SequenceMatcher;
import net.byteseek.searcher.ForwardSearchIterator;
import net.byteseek.searcher.SearchResult;
import net.byteseek.searcher.Searcher;
import net.byteseek.searcher.sequence.SequenceMatcherSearcher;

public class AltoByteOffsetsParser {
  private static final Searcher<SequenceMatcher> CONTENT_SEARCHER =
      new SequenceMatcherSearcher(new ByteSequenceMatcher("CONTENT=\""));
  private static final Searcher<SequenceMatcher> QUOTE_SEARCHER =
      new SequenceMatcherSearcher(new ByteSequenceMatcher("\""));

  public static void parse(byte[] altoBytes, OutputStream os) throws IOException {
    ForwardSearchIterator<SequenceMatcher> it = new ForwardSearchIterator<SequenceMatcher>(
        CONTENT_SEARCHER, altoBytes);

    while (it.hasNext()) {
      for (SearchResult<SequenceMatcher> m : it.next()) {
        int start = (int) m.getMatchPosition() + 9;
        int end = (int) new ForwardSearchIterator<>(
            QUOTE_SEARCHER, altoBytes, start).next().get(0).getMatchPosition();
        os.write(altoBytes, start, end - start);
        os.write(String.format("⚑%d ", start).getBytes(StandardCharsets.UTF_8));
      }
    }
  }

  public static void main(String[] args) throws IOException {
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    parse(Files.readAllBytes(Paths.get("src/test/resources/data/alto.xml")), bos);
    String text = bos.toString(StandardCharsets.UTF_8.toString());
    System.out.println(text);
  }
}
