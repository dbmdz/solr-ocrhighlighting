# Performance Considerations

Highlighting based on locally stored files can take a long time, depending on the environment. This section gives some
hints on potential knobs to tune to improve the performance.

!!! note "Use JDK >= 9"
    Java 9 introduced a feature called [String Compaction](https://openjdk.java.net/jeps/254)
    that has a significant impact on several hot code paths used during OCR highlighting.
    **You can expect a reduction in runtime of more than 50% if you use a JVM with string compaction
    enabled compared to one without** (assuming you're running on flash storage).
    We highly recommend using the latest LTS OpenJDK version released after Java 9, which
    as of September 2021 is OpenJDK 17.

## Performance Analysis
Before you start tuning the plugin, it is important to spend some time on analyzing the nature of the problems:

- Check Solr queries with `debug=timing`: How much of the response time is actually spent in the OCR highlighting
  component?
- On newer Linux kernels, check the Pressure Stall Information (PSI) metrics with `htop` or by looking
  at `/proc/pressure/{io,cpu}`. This can give you an indication if the system is I/O-bottlenecked or
  CPU-bottlenecked.
- On the operating system level (if you're on a Linux system), use [BCC Tools](https://github.com/iovisor/bcc),
  especially `{nfs/xfs/ext/...}slower` and `{nfs/xfs/ext/...}dist` to check if the performance issues are due to I/O
  latency.

## Storage Layer
The plugin spends a lot of time reading small sections of the target files from disk. This means that
the performance characteristics of the underlying storage system have a huge effect on the performance
of the plugin.

Important factors include:

- *Random Read Latency*: What is the time required to seek to a random location and read a small chunk?
- *Number of possible parallel reads* (see below): Does the storage layer support more than one active reader?

Generally speaking, local storage is better than remote storage (like NFS or CIFS), due to the network latency, and
flash-based storage is better than disk-based storage, due to the lower random read latency and the possibility to
do parallel reads. A RAID1/10 setup is preferred over a RAID0/JBOD setup, due to the increased potential for parallel reads.

When building passages during highlighting (i.e. determining where a snippet starts and ends), the plugin reads
the OCR files in aligned sections and caches these to reduce the number of reads and allocations. The bigger
the cache size, the more data is read from the disk, i.e. the chances of cache hits increase. However, this
comes at the cost of more memory usage and more allocations in the JVM, which can have a performance impact.
By default, the plugin uses a section size of 8KiB with a maximum number of cached sections of 10,
which is a good trade-off for most setups and performed well in our benchmarks. If you want to tweak these
settings, use the `sectionReadSizeKiB` and `maxSectionCacheSizeKiB` parameters on the `OcrHighlightComponent`
in your `solrconfig.xml`:

- `sectionReadSizeKiB`: The size of the sections that are read from the OCR files. The default is 8KiB.
- `maxSectionCacheSizeKiB`: The maximum memory that is used for caching sections. The default is 10 * `sectionReadSizeKiB`.

## Concurrency
The plugin can read multiple files in parallel and also process them concurrently. By default, it will
use as many threads as there are available logical CPU cores on the machine, but this can be tweaked
with the `numHighlightingThreads` and `maxQueuedPerThread` parameters on the `OcrHighlightComponent`
in your `solrconfig.xml`. Tune these parameters to match your hardware and storage layer.

- `numHighlightingThreads`: The number of threads that will be used to read and process the OCR files.
   Defaults to the number of logical CPU cores. Set this higher if you're I/O-bottlenecked and can
   support more parallel reads than you have logical CPU cores (very likely for modern NVMe drives).
- `maxQueuedPerThread`: The thread pool used to highlight documents is shared across all requests.
  By default, we queue only a limited number of documents per thread as to not
  stall other requests. If this number is reached, all remaining highlighting
  will be done single-threaded on the request thread. You usually don't have to
  touch this setting, but if you have large result sets with many concurrent
  requests, this can help to reduce the number of threads that are active at
  the same time, at least as a stopgap.

## Runtime configuration
Another option to influence the performance of the plugin is to tune some runtime options for highlighting.
For any of these, refer to the [Querying section](https://dbmdz.github.io/solr-ocrhighlighting/query/) for more details.

- If you're storing documents at the page-level in the index, you can set the `hl.ocr.trackPages` parameter to `false`
  (default is `true`). This will skip seeking backward in the input from the match position to find the containing
  page, which can be costly.
- Tune the number of candidate passages for ranking with `hl.ocr.maxPassages`, which defaults to `100`. Lowering this is
  better for performance, but means that the resulting snippets might not be the most relevant in the document.
- Change the limit (`hl.ocr.limitBlock`) and/or context block types (`hl.ocr.contextBlock`) to something lower in the
  block hierarchy to reduce the amount of reads in the OCR files. Another knob to tune is the number of context blocks
  for each hit (`hl.ocr.contextSize`), with the same effect.
- The last resort if highlighting takes too long is to pass the `hl.ocr.timeAllowed` parameter, which stops
  highlighting any further documents if a given timeout is exceeded.
