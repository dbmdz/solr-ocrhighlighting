package de.digitalcollections.solrocr.util;

import com.google.common.collect.Streams;
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
import org.apache.commons.lang3.tuple.Pair;

public class MiniOcrByteOffsetsParser {

  private static final Searcher<SequenceMatcher> BEGIN_WORD_SEARCHER =
      new SequenceMatcherSearcher(new ByteSequenceMatcher("<w x=\""));
  private static final Searcher<SequenceMatcher> END_WORD_SEARCHER =
      new SequenceMatcherSearcher(new ByteSequenceMatcher("</w>"));

  private static int getClosingOffsetFrom(byte[] ocrBytes, char tag, int fromOffset) {
    final Searcher<SequenceMatcher> searcher = new SequenceMatcherSearcher(new ByteSequenceMatcher(
        "</" + tag + ">"));
    final ForwardSearchIterator<SequenceMatcher> it = new ForwardSearchIterator<SequenceMatcher>(
        searcher, ocrBytes, fromOffset);
    if (!it.hasNext()) {
      throw new IllegalArgumentException("Invalid MiniOCR, could not find closing tag for '" + tag + "'");
    }
    return (int) it.next().get(0).getMatchPosition();
  }

  private static int getIdOffset(byte[] ocrBytes, int startOffset, String id) {
    final Searcher<SequenceMatcher> idSearcher;
    try {
      idSearcher = new SequenceMatcherSearcher(SequenceMatcherCompiler.compileFrom(
          "'<' . ' xml:id=\"" + id + "'"));
    } catch (CompileException e) {
      throw new RuntimeException(e);
    }
    ForwardSearchIterator<SequenceMatcher> it = new ForwardSearchIterator<>(idSearcher, ocrBytes, startOffset);
    if (!it.hasNext()) {
      throw new IllegalArgumentException("Could not find element with id '" + id + "'");
    }
    return (int) it.next().get(0).getMatchPosition();
  }

  public static List<Pair<String, Integer>> parse(byte[] ocrBytes, int startOffset, String firstId, String lastId) throws IOException {
    if (firstId != null) {
      startOffset = getIdOffset(ocrBytes, startOffset, firstId);
    }
    int endOffset = ocrBytes.length - 1;
    if (lastId != null && lastId.equals("\uFFFF")) {
      char tag = new String(ocrBytes, startOffset, 6, StandardCharsets.UTF_8).charAt(1);
      endOffset = getClosingOffsetFrom(ocrBytes, tag, startOffset);
    } else if (lastId != null) {
      int lastOffset = getIdOffset(ocrBytes, startOffset, lastId);
      char tag = new String(ocrBytes, lastOffset, 6, StandardCharsets.UTF_8).charAt(1);
      endOffset = getClosingOffsetFrom(ocrBytes, tag, lastOffset);
    }
    ForwardSearchIterator<SequenceMatcher> beginIt = new ForwardSearchIterator<>(
        BEGIN_WORD_SEARCHER, startOffset, endOffset, ocrBytes);
    ForwardSearchIterator<SequenceMatcher> endIt = new ForwardSearchIterator<>(
        END_WORD_SEARCHER, startOffset, endOffset, ocrBytes);

    return
        Streams.zip(
            Streams.stream(beginIt).flatMap(Collection::stream).map(SearchResult::getMatchPosition),
            Streams.stream(endIt).flatMap(Collection::stream).map(SearchResult::getMatchPosition),
            ImmutablePair::new)
        .map(offsets -> mapOffsetsToTerm(offsets, ocrBytes))
        .collect(Collectors.toList());
  }

  private static Pair<String, Integer> mapOffsetsToTerm(Pair<Long, Long> offsets, byte[] ocrBytes) {
    int start = offsets.getLeft().intValue();
    int end = offsets.getRight().intValue();
    int startTerm = ArrayUtils.indexOf(ocrBytes, (byte) '>', start) + 1;
    assert startTerm < end;
    int termWidth = (end - startTerm);
    return ImmutablePair.of(new String(ocrBytes, startTerm, termWidth, StandardCharsets.UTF_8), startTerm);
  }

  public static void parse(byte[] ocrBytes, OutputStream os) throws IOException {
    parse(ocrBytes, os, null, null);
  }

  public static void parse(byte[] ocrBytes, OutputStream os, String onlyId) throws IOException {
    parse(ocrBytes, os, onlyId, "\uFFFF");
  }

  public static void parse(byte[] ocrBytes, OutputStream os, String firstId, String lastId) throws IOException {
    for (Pair<String, Integer> pair : parse(ocrBytes, 0, firstId, lastId)) {
      os.write(pair.getLeft().getBytes(StandardCharsets.UTF_8));
      os.write(String.format("⚑%d ", pair.getRight()).getBytes(StandardCharsets.UTF_8));
    }
  }

  public static void main(String[] args) throws IOException {
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    long start = System.nanoTime();
    parse(Files.readAllBytes(Paths.get("src/test/resources/data/31337_utf8ocr.xml")), bos, "28");
    System.out.println(String.format("Parsing took %.2fms", (System.nanoTime() - start) / 1e6));
    String text = bos.toString(StandardCharsets.UTF_8.toString());
    System.out.println(text);
  }
}
