package de.digitalcollections.solrocr.util;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import de.digitalcollections.solrocr.util.SourcePointer.FileSource;
import de.digitalcollections.solrocr.util.SourcePointer.Region;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Utility to concurrently "warm" the OS page cache with files that will be used for highlighting.
 *
 * Should significantly reduce the I/O latency during the sequential highlighting process, especially when using
 * a network storage layer or a RAID system.
 *
 * The idea is that a lot of storage layers can benefit from parallel I/O. Unfortunately, snippet generation with the
 * current UHighlighter approach is strongly sequential, which means we give away a lot of potential performance, since
 * we're limited by the I/O latency of the underlying storage layer. By pre-reading the data we might need in a
 * concurrent way, we pre-populate the operating system's page cache, so any I/O performed by the snippet generation
 * process further down the line should only hit the page cache and not incur as much of a latency hit.
 *
 * The class also provides a way to cancel the pre-loading of a given source pointer. This is called at the beginning
 * of the snippet generation process, since at that point any background I/O on the target files will only add to the
 * latency we might experience anyway.
 */
public class PageCacheWarmer {
  private static int BUF_SIZE = 32 * 1024;
  private static int NUM_THREADS = 8;
  private static int MAX_PENDING_JOBS = 128;

  // This is the read buffer for every worker thread, so we only do as many allocations as necessary
  private static ThreadLocal<ByteBuffer> BUF = ThreadLocal.withInitial(() -> ByteBuffer.allocate(BUF_SIZE));

  // Set of pending preload operations for file sources, used to allow the cancelling of preloading tasks
  private static final Set<FileSource> pendingPreloads = ConcurrentHashMap.newKeySet(MAX_PENDING_JOBS);

  private static final ExecutorService service = new ThreadPoolExecutor(
      NUM_THREADS, NUM_THREADS, 0, TimeUnit.MILLISECONDS, new LinkedBlockingDeque<>(MAX_PENDING_JOBS),
      new ThreadFactoryBuilder().setNameFormat("solr-ocrhighlighting-cache-warmer-%d").build(),
      new ThreadPoolExecutor.DiscardOldestPolicy());


  /**
   * Reads the file source in 32KiB chunks
   * @param src file source
   */
  private static void preload(FileSource src) {
    pendingPreloads.add(src);
    ByteBuffer buf = BUF.get();
    try (SeekableByteChannel channel = Files.newByteChannel(src.path, StandardOpenOption.READ)) {
      for (Region region : src.regions) {
        channel.position(region.start);
        int remainingSize = region.end - region.start;
        while (remainingSize > 0 && pendingPreloads.contains(src)) {
          remainingSize -= channel.read(buf);
          if (Thread.interrupted()) {
            return;
          }
        }
      }
    } catch (IOException e) {
      // NOP, this method only serves to populate the page cache, so we don't care about I/O errors.
    } finally {
      pendingPreloads.remove(src);
    }
  }

  /**
   * Populate the OS page cache with the targets of the source pointer.
   */
  public static void preload(SourcePointer ptr) {
    if (ptr == null) {
      return;
    }
    for (FileSource source : ptr.sources) {
      if (pendingPreloads.contains(source)) {
        continue;
      }
      service.submit(() -> preload(source));
    }
  }

  /**
   * Cancel all running and pending preloading tasks for the given source pointer.
   */
  public static void cancelPreload(SourcePointer ptr) {
    if (ptr == null) {
      return;
    }
    ptr.sources.forEach(pendingPreloads::remove);
  }

  public static void shutdown() {
    service.shutdownNow();
  }
}
