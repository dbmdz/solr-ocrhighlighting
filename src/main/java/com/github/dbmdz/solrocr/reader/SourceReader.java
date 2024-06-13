package com.github.dbmdz.solrocr.reader;

import com.github.dbmdz.solrocr.model.SourcePointer;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;

/** API for reading data from a source. */
public interface SourceReader {
  /** Close the resources associated with this reader. */
  void close() throws IOException;

  /** Get the pointer this reader is reading from. */
  SourcePointer getPointer();

  /** Get the internal identifier of the source. */
  String getIdentifier();

  /** Get the number of bytes in the source. */
  int length() throws IOException;

  /**
   * Read a section from the source as an ASCII/Latin1 string.
   *
   * <p>This method should be implemented as fast as possible, ideally without going through a
   * decoder.
   */
  String readAsciiString(int start, int len) throws IOException;

  /** Read a section from the source as an UTF8 string. */
  String readUtf8String(int start, int byteLen) throws IOException;

  /**
   * Read a section aligned to this reader's section size.
   *
   * <p>This method should be implemented as efficiently as possible, since it's called in the
   * hottest loop of the highlighting logic (passage formation)
   */
  Section getAsciiSection(int offset) throws IOException;

  /**
   * Read into {@param dst} starting at {@param start} from the source. , returning the number of
   * bytes read.
   */
  int readBytes(ByteBuffer dst, int start) throws IOException;

  default int readBytes(byte[] dst, int dstOffset, int start, int len) throws IOException {
    return readBytes(ByteBuffer.wrap(dst, dstOffset, len), start);
  }

  /**
   * Get a {@link java.nio.channels.SeekableByteChannel} for this SourceReader.
   *
   * <p>This is a generic implementation that should be overriden with a more efficient
   * source-specific implementation, if available.
   */
  default SeekableByteChannel getByteChannel() throws IOException {
    return new SeekableByteChannel() {
      int position = 0;
      boolean closed = false;

      @Override
      public int read(ByteBuffer byteBuffer) throws IOException {
        int numRead = SourceReader.this.readBytes(byteBuffer, position);
        this.position += numRead;
        return numRead;
      }

      @Override
      public int write(ByteBuffer byteBuffer) throws IOException {
        throw new UnsupportedOperationException("Channel is read-only");
      }

      @Override
      public long position() throws IOException {
        return position;
      }

      @Override
      public SeekableByteChannel position(long newPosition) throws IOException {
        this.position = (int) newPosition;
        return this;
      }

      @Override
      public long size() throws IOException {
        return SourceReader.this.length();
      }

      @Override
      public SeekableByteChannel truncate(long l) throws IOException {
        throw new UnsupportedOperationException("Channel is read-only");
      }

      @Override
      public boolean isOpen() {
        return !this.closed;
      }

      @Override
      public void close() throws IOException {
        SourceReader.this.close();
        this.closed = true;
      }
    };
  }

  class Section {
    public final int start;
    public final int end;
    public final String text;

    public Section(int start, int end, String text) {
      /* Start byte offset of the section, inclusive */
      this.start = start;
      /* End byte offset of the section, exclusive */
      this.end = end;
      /* ASCII text of the section */
      this.text = text;
    }

    @Override
    public String toString() {
      return "Section{" + "start=" + start + ", end=" + end + '}';
    }
  }
}
