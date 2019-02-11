package org.mdz.search.solrocr.formats.mini;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import net.byteseek.matcher.sequence.ByteSequenceMatcher;
import net.byteseek.matcher.sequence.SequenceMatcher;
import net.byteseek.searcher.ForwardSearchIterator;
import net.byteseek.searcher.SearchResult;
import net.byteseek.searcher.Searcher;
import net.byteseek.searcher.sequence.SequenceMatcherSearcher;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.mdz.search.solrocr.util.Streams;

public class MiniOcrByteOffsetsParser {

  private static final Searcher<SequenceMatcher> BEGIN_WORD_SEARCHER =
      new SequenceMatcherSearcher(new ByteSequenceMatcher("<w x=\""));
  private static final Searcher<SequenceMatcher> END_WORD_SEARCHER =
      new SequenceMatcherSearcher(new ByteSequenceMatcher("</w>"));

  private static int getPageOffset(byte[] ocrBytes, String pageId) {
    final Searcher<SequenceMatcher> pageSearcher =
        new SequenceMatcherSearcher(new ByteSequenceMatcher("<p xml:id=\"" + pageId));
    ForwardSearchIterator<SequenceMatcher> it = new ForwardSearchIterator<>(pageSearcher, ocrBytes);
    if (!it.hasNext()) {
      throw new IllegalArgumentException("Could not find page with id '" + pageId + "'");
    }
    return (int) it.next().get(0).getMatchPosition();
  }

  public static void parse(byte[] ocrBytes, OutputStream os) throws IOException {
    parse(ocrBytes, os, null, null);
  }

  /** Convert the hOCR document, starting from startPage and ending at, <strong>not including</strong> endPage. */
  public static void parse(byte[] ocrBytes, OutputStream os, String startPage, String endPage) throws IOException {
    int startOffset = 0;
    if (startPage != null) {
      startOffset = getPageOffset(ocrBytes, startPage);
    }
    int endOffset = ocrBytes.length - 1;
    if (endPage != null) {
      endOffset = getPageOffset(ocrBytes, endPage);
    }
    ForwardSearchIterator<SequenceMatcher> beginIt = new ForwardSearchIterator<>(
        BEGIN_WORD_SEARCHER, startOffset, endOffset, ocrBytes);
    ForwardSearchIterator<SequenceMatcher> endIt = new ForwardSearchIterator<>(
        END_WORD_SEARCHER, startOffset, endOffset, ocrBytes);

    List<ImmutablePair<Long, Long>> wordOffsets =
        Streams.zip(Streams.stream(beginIt).flatMap(Collection::stream).map(SearchResult::getMatchPosition),
            Streams.stream(endIt).flatMap(Collection::stream).map(SearchResult::getMatchPosition),
            ImmutablePair::new).collect(Collectors.toList());

    for (ImmutablePair<Long, Long> p : wordOffsets) {
      int start = p.left.intValue();
      int end = p.right.intValue();
      int startTerm = ArrayUtils.indexOf(ocrBytes, (byte) '>', start) + 1;
      assert startTerm < end;
      int termWidth = (end - startTerm);
      os.write(ocrBytes, startTerm, termWidth);
      os.write(String.format("⚑%d ", startTerm).getBytes(StandardCharsets.UTF_8));
    }
  }

  public static void main(String[] args) throws IOException {
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    long start = System.nanoTime();
    parse(Files.readAllBytes(Paths.get("src/test/resources/data/31337_utf8ocr.xml")), bos, "28", "30");
    System.out.println(String.format("Parsing took %.2fms", (System.nanoTime() - start) / 1e6));
    String text = bos.toString(StandardCharsets.UTF_8.toString());
    System.out.println(text);
  }
}
