package com.github.dbmdz.solrocr.reader;

import com.github.dbmdz.solrocr.model.SourcePointer;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.StatObjectArgs;
import io.minio.errors.*;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SeekableByteChannel;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import org.apache.solr.common.SolrException;

public class S3ObjectSourceReader extends BaseSourceReader {
  private final String bucketName;
  private final String key;
  private final MinioClient s3Client;
  private int fileSizeBytes = -1;

  public S3ObjectSourceReader(
      MinioClient s3Client, URI uri, SourcePointer ptr, int sectionSize, int maxCacheEntries) {
    super(ptr, sectionSize, maxCacheEntries);
    this.s3Client = s3Client;
    this.bucketName = uri.getHost();
    this.key = uri.getPath().substring(1);
    validateSource(uri);
  }

  @Override
  public int length() throws IOException {
    if (fileSizeBytes == -1) {
      try {
        this.fileSizeBytes =
            (int)
                s3Client
                    .statObject(StatObjectArgs.builder().bucket(bucketName).object(key).build())
                    .size();
      } catch (MinioException | NoSuchAlgorithmException | InvalidKeyException e) {
        throw new IOException("Failed to determine size of S3 object " + getIdentifier(), e);
      }
    }
    return this.fileSizeBytes;
  }

  @Override
  public int readBytes(ByteBuffer dst, int start) throws IOException {
    try (InputStream stream =
            s3Client.getObject(
                GetObjectArgs.builder()
                    .bucket(bucketName)
                    .object(key)
                    .offset((long) start)
                    .build());
        ReadableByteChannel channel = Channels.newChannel(stream)) {
      return channel.read(dst);
    } catch (MinioException | NoSuchAlgorithmException | InvalidKeyException e) {
      throw new IOException(
          "Failed to read S3 object " + getIdentifier() + " at offset " + start, e);
    }
  }

  @Override
  public SeekableByteChannel getByteChannel() throws IOException {
    return new SeekableByteChannel() {
      long position = 0;
      ReadableByteChannel chan = getChannel(position);

      private ReadableByteChannel getChannel(long offset) throws IOException {
        try {
          InputStream stream =
              s3Client.getObject(
                  GetObjectArgs.builder().bucket(bucketName).object(key).offset(offset).build());
          return Channels.newChannel(stream);
        } catch (MinioException | NoSuchAlgorithmException | InvalidKeyException e) {
          throw new IOException(
              "Failed to read S3 object " + getIdentifier() + " at offset " + offset, e);
        }
      }

      @Override
      public int read(ByteBuffer byteBuffer) throws IOException {
        int numRead = this.chan.read(byteBuffer);
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
        if (newPosition == position) {
          return this;
        }
        this.chan.close();
        this.chan = getChannel(newPosition);
        this.position = newPosition;
        return this;
      }

      @Override
      public long size() throws IOException {
        return S3ObjectSourceReader.this.length();
      }

      @Override
      public SeekableByteChannel truncate(long l) throws IOException {
        throw new UnsupportedOperationException("Channel is read-only");
      }

      @Override
      public boolean isOpen() {
        return this.chan.isOpen();
      }

      @Override
      public void close() throws IOException {
        this.chan.close();
      }
    };
  }

  @Override
  public void close() throws IOException {
    // NOP
  }

  private void validateSource(URI uri) {
    if (s3Client == null) {
      throw new SolrException(
          SolrException.ErrorCode.BAD_REQUEST,
          "Requested S3 object but no S3 client was configured.");
    }
    try {
      if (!s3Client.bucketExists(BucketExistsArgs.builder().bucket(uri.getHost()).build())) {
        throw new SolrException(
            SolrException.ErrorCode.BAD_REQUEST,
            String.format(Locale.US, "S3 bucket at %s does not exist.", uri));
      }
    } catch (MinioException | IOException | NoSuchAlgorithmException | InvalidKeyException e) {
      throw new SolrException(
          SolrException.ErrorCode.BAD_REQUEST,
          String.format(Locale.US, "Could not reach S3 bucket at %s", uri),
          e);
    }
  }

  @Override
  public String getIdentifier() {
    return String.format("s3://%s/%s", bucketName, key);
  }
}
