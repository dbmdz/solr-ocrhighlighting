package org.mdz.search.solrocr.formats.hocr;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import net.byteseek.compiler.CompileException;
import net.byteseek.compiler.matcher.SequenceMatcherCompiler;
import net.byteseek.matcher.sequence.ByteSequenceMatcher;
import net.byteseek.matcher.sequence.SequenceMatcher;
import net.byteseek.searcher.ForwardSearchIterator;
import net.byteseek.searcher.SearchResult;
import net.byteseek.searcher.Searcher;
import net.byteseek.searcher.sequence.SequenceMatcherSearcher;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.mdz.search.solrocr.util.Streams;

public class HocrByteOffsetsParser {

  private static final Searcher<SequenceMatcher> BEGIN_SPAN_SEARCHER =
      new SequenceMatcherSearcher(new ByteSequenceMatcher("<span"));
  private static final Searcher<SequenceMatcher> END_SPAN_SEARCHER =
      new SequenceMatcherSearcher(new ByteSequenceMatcher("</span>"));

  private static int getPageOffset(byte[] ocrBytes, String pageId) {
    final Searcher<SequenceMatcher> pageSearcher;
    try {
      pageSearcher = new SequenceMatcherSearcher(SequenceMatcherCompiler.compileFrom(
          "'<div class=' . 'ocr_page' . ' id=' . '" + pageId + "'"));
    } catch (CompileException e) {
      throw new RuntimeException(e);
    }
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

    Searcher<SequenceMatcher> termSearcher;
    try {
      termSearcher = new SequenceMatcherSearcher(SequenceMatcherCompiler.compileFrom("'>' ^'<'"));
    } catch (CompileException e) {
      throw new RuntimeException();
    }

    ForwardSearchIterator<SequenceMatcher> beginIt = new ForwardSearchIterator<>(
        BEGIN_SPAN_SEARCHER, startOffset, endOffset, ocrBytes);
    ForwardSearchIterator<SequenceMatcher> endIt = new ForwardSearchIterator<>(
        END_SPAN_SEARCHER,startOffset, endOffset, ocrBytes);

    List<ImmutablePair<Long, Long>> wordOffsets =
        Streams.zip(Streams.stream(beginIt).flatMap(Collection::stream).map(SearchResult::getMatchPosition),
                    Streams.stream(endIt).flatMap(Collection::stream).map(SearchResult::getMatchPosition),
                    ImmutablePair::new)
        .filter(p -> {
          String hocrClass = new String(ocrBytes, p.left.intValue() + 13, 9, StandardCharsets.UTF_8);
          return hocrClass.equals("ocrx_word");
        }).collect(Collectors.toList());
    for (ImmutablePair<Long, Long> p : wordOffsets) {
      int start = Math.toIntExact(p.left);
      int end = Math.toIntExact(p.right);
      ForwardSearchIterator<SequenceMatcher> termIt = new ForwardSearchIterator<>(termSearcher, start, end, ocrBytes);
      if (!termIt.hasNext()) {
        continue;
      }
      int startTerm = (int) (termIt.next().get(0).getMatchPosition()) + 1;
      int endTerm = ArrayUtils.indexOf(ocrBytes, (byte) '<', startTerm);
      int width = endTerm - startTerm;
      os.write(ocrBytes, startTerm, width);
      os.write(String.format("⚑%d ", startTerm).getBytes(StandardCharsets.UTF_8));
    }
  }

  public static void main(String[] args) throws IOException {
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    long start = System.nanoTime();
    parse(Files.readAllBytes(Paths.get("src/test/resources/data/hocr_test.html")), bos, "page_118", "page_120");
    System.out.println(String.format("Parsing took %.2fms", (System.nanoTime() - start) / 1e6));
    String text = bos.toString(StandardCharsets.UTF_8.toString());
    System.out.println(text);
  }
}
