# Performance Considerations

Highlighting based on locally stored files can take a long time, depending on the environment. This section gives some
hints on potential knobs to tune to improve the performance.

## Performance Analysis
Before you start tuning the plugin, it is important to spend some time on analyzing the nature of the problems.
- Check Solr queries with `debugQuery=true`: How much of the response time is actually spent in the OCR highlighting
  component?
- On the operating system level (if you're on a Linux system), use [BCC Tols](https://github.com/iovisor/bcc),
  especially `{nfs/xfs/ext/...}slower` and `{nfs/xfs/ext/...}dist` to check if the performance issues are due to I/O
  latency.

## Storage Layer
A lot of time in the plugin is spent on randomly reading small sections of the target files from disk. This means that
the performance characteristics of the underlying storage system have a huge effect on the performance of the plugin.
Important factors include:
    * *Random Read Latency*: What is the time required to seek to a random location and read a small chunk?
    * *Number of possible parallel reads* (see below): Does the storage layer support more than one active reader?

Generally speaking, local storage is better than remote storage (like NFS or CIFS), due to the network latency, and
flash-based storage is better than harddisk-based storage, due to the lower random read latency. A RAID setup is
preferred over a JBOD setup, due to the potential for parallel reads.

## Plugin configuration
The plugin offers the possibility to perform a concurrent read-ahead of highlighting target files. This will perform
"dummy" reads on a multiple parallel threads, with the intent to fill the operating system's page cache with the contents
of the highlighting targets, so that the actual highlighting process is performed on data read from the cache (which
resides in main memory). This is mainly useful for storage layers that benefit from parallel reads, since the highlighting
process is strongly sequential and performing the read-ahead concurrently can reduce latency.

To enable it, add the `enablePreload=true` attribute on the OCR highlighting component in your core's `solrconfig.xml`.
It is important to accompany this with benchmarking and monitoring, the available settings should be tuned to the
environment:
- `preloadReadSize`: Size in bytes of read-ahead block reads, should be aligned with file system block size
  (or `rsize` for NFS file systems). Defaults to `32768`
- `preloadConcurrency`: Number of threads to perform read-ahead. Optimal settings have to be determined via
  experimentation. Defaults to `8`.

This approach relies on the OS-level page cache, so make sure you have enough spare RAM available on your machine to
actually benefit from this! Use BCC's `*slower` tools to verify that it's a `solr-ocrhighlight` thread that performs
most of the reads and not the actual query thread (`qtp....`). If you run the same query twice, you shouldn't see a lot
reads from either the `qtp...` or `solr-ocrhlighight` threads.

Example configuration tuned for remote NFS storage mounted with `rsize=65536`:
```xml
<searchComponent class="de.digitalcollections.solrocr.solr.OcrHighlightComponent"
                 name="ocrHighlight" enablePreload="true" preloadReadSize="65536" preloadConcurrency="8"/>
```


## Runtime configuration
Another option to influence the performance of the plugin is to tune some runtime options for highlighting.
For any of these, refer to the [Querying section](https://dbmdz.github.io/solr-ocrhighlighting/query/) for more details.

- Tune the number of candidate passages for ranking with `hl.ocr.maxPassages`, wich defaults to `100`. Lowering this is
  better for performance, but means that the resulting snippets might not be the most relevant in the document.
- Change the limit (`hl.ocr.limitBlock`) and/or context block types (`hl.ocr.contextBlock`) to something lower in the
  block hierarchy to reduce the amount of reads in the OCR files. Another knob to tune is the number of context blocks
  for each hit (`hl.ocr.contextSize`), with the same effect.
- The last resort if highlighting takes too long is to pass the `hl.ocr.timeAllowed` parameter, which stops
  highlighting any further documents if a given timeout is exceeded.
