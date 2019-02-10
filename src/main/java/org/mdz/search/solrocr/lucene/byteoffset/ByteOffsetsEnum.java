package org.mdz.search.solrocr.lucene.byteoffset;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.PriorityQueue;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.IOUtils;

/**
 * Customization of {@link org.apache.lucene.search.uhighlight.OffsetsEnum} to load the byte offset from payloads.
 */
public abstract class ByteOffsetsEnum implements Comparable<ByteOffsetsEnum>, Closeable {
  @Override
  public int compareTo(ByteOffsetsEnum other) {
    try {
      int cmp = Integer.compare(byteOffset(), other.byteOffset());
      if (cmp != 0) {
        return cmp;
      }
      final BytesRef thisTerm = this.getTerm();
      final BytesRef otherTerm = other.getTerm();
      if (thisTerm == null || otherTerm == null) {
        if (thisTerm == null && otherTerm == null) {
          return 0;
        } else if (thisTerm == null) {
          return 1; // put "this" (wildcard mtq enum) last
        } else {
          return -1;
        }
      }
      return thisTerm.compareTo(otherTerm);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  public abstract boolean nextPosition() throws IOException;

  public abstract int freq() throws IOException;

  public abstract BytesRef getTerm() throws IOException;

  public abstract int byteOffset() throws IOException;

  @Override
  public void close() throws IOException {
  }

  @Override
  public String toString() {
    final String name = getClass().getSimpleName();
    String offset = "";
    try {
      offset = "@" + byteOffset();
    } catch (Exception e) {
      //ignore; for debugging only
    }
    try {
      return name + "(term:" + getTerm().utf8ToString() + offset + ")";
    } catch (Exception e) {
      return name;
    }
  }

  public static class OfPostings extends ByteOffsetsEnum {
    private final BytesRef term;
    private final PostingsEnum postingsEnum; // with offsets
    private final int freq;

    private int posCounter = -1;

    public OfPostings(BytesRef term, int freq, PostingsEnum postingsEnum) throws IOException {
      this.term = Objects.requireNonNull(term);
      this.postingsEnum = Objects.requireNonNull(postingsEnum);
      this.freq = freq;
      this.posCounter = this.postingsEnum.freq();
    }

    public OfPostings(BytesRef term, PostingsEnum postingsEnum) throws IOException {
      this(term, postingsEnum.freq(), postingsEnum);
    }

    public PostingsEnum getPostingsEnum() {
      return postingsEnum;
    }

    @Override
    public boolean nextPosition() throws IOException {
      if (posCounter > 0) {
        posCounter--;
        postingsEnum.nextPosition();
        return true;
      } else {
        return false;
      }
    }

    @Override
    public int freq() throws IOException {
      return freq;
    }

    @Override
    public BytesRef getTerm() {
      return term;
    }

    @Override
    public int byteOffset() throws IOException {
      return ByteOffsetEncoder.decode(postingsEnum.getPayload());
    }
  }
  public static final ByteOffsetsEnum EMPTY = new ByteOffsetsEnum() {
    @Override
    public boolean nextPosition() throws IOException {
      return false;
    }

    @Override
    public BytesRef getTerm() throws IOException {
      throw new UnsupportedOperationException();
    }

    @Override
    public int byteOffset() throws IOException {
      throw new UnsupportedOperationException();
    }

    @Override
    public int freq() throws IOException {
      return 0;
    }
  };

    public static class MultiByteOffsetsEnum extends ByteOffsetsEnum {

    private final PriorityQueue<ByteOffsetsEnum> queue;
    private boolean started = false;

    public MultiByteOffsetsEnum(List<ByteOffsetsEnum> inner) throws IOException {
      this.queue = new PriorityQueue<>();
      for (ByteOffsetsEnum oe : inner) {
        if (oe.nextPosition())
          this.queue.add(oe);
      }
    }

    @Override
    public boolean nextPosition() throws IOException {
      if (!started) {
        started = true;
        return this.queue.size() > 0;
      }
      if (this.queue.size() > 0) {
        ByteOffsetsEnum top = this.queue.poll();
        if (top.nextPosition()) {
          this.queue.add(top);
          return true;
        }
        else {
          top.close();
        }
        return this.queue.size() > 0;
      }
      return false;
    }

    @Override
    public BytesRef getTerm() throws IOException {
      return this.queue.peek().getTerm();
    }

    @Override
    public int byteOffset() throws IOException {
      return this.queue.peek().byteOffset();
    }

    @Override
    public int freq() throws IOException {
      return this.queue.peek().freq();
    }

    @Override
    public void close() throws IOException {
      // most child enums will have been closed in .nextPosition()
      // here all remaining non-exhausted enums are closed
      IOUtils.close(queue);
    }
  }
}
