package com.github.dbmdz.solrocr.lucene.filters;

import com.github.dbmdz.solrocr.reader.StreamDecoder;
import java.io.IOException;
import java.io.Reader;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;

/**
 * A Reader implementation that reads from a SeekableByteChannel and allows repositioning the
 * reader.
 */
public class ByteSeekableReader extends Reader {
  private final SeekableByteChannel channel;
  private StreamDecoder decoder;

  public ByteSeekableReader(SeekableByteChannel channel) {
    this.channel = channel;
    this.decoder = StreamDecoder.forDecoder(channel, StandardCharsets.UTF_8.newDecoder(), -1);
  }

  @Override
  public int read(char[] cbuf, int off, int len) throws IOException {
    return this.decoder.read(cbuf, off, len);
  }

  /** Return the current byte position in the underlying channel. */
  public int position() throws IOException {
    return (int) this.channel.position();
  }

  /**
   * Reposition the reader to the given byte position.
   *
   * <p>This will also reset the decoder.
   */
  public void position(int newPosition) throws IOException {
    this.channel.position(newPosition);
    this.decoder = StreamDecoder.forDecoder(channel, StandardCharsets.UTF_8.newDecoder(), -1);
  }

  @Override
  public void close() throws IOException {
    this.channel.close();
  }
}
