# Indexing OCR documents

!!! note "If you want to store the OCR in the index itself you can all but _skip this section_"
    Just put the OCR content in the field and submit it to Solr for indexing. We recommend using the space-efficient
    [MiniOCR format](./formats.md#miniocr) if you decide to go this way.

Indexing OCR documents without storing the actual content in the index is relatively simple:
When building the index document, instead of putting  the actual OCR content into the field, you use
a **source pointer**. This pointer will tell the plugin from which location to load the OCR content
during indexing and highlighting.

The advantage of this approach is a *significant* reduction in the amount of memory required for both the client
and the Solr server, since neither of them has to keep the (potentially very large) OCR document in memory at
any time. The client just has a very short pointer, and the plugin will load the contents *lazily*. Additionally,
the index size is kept comparatively small, since Solr only needs to store the locations of the contents and not
the (again, potentially very large) contents themselves in the index.

!!! note "Performance"
    When using external files for highlighting, the performance depends to a large degree on
    how fast the underlying storage is able to perform random I/O. This is why **we highly recommend
    using flash storage for the documents**.
    
    Another option to increase highlighting performance is
    to **switch from UTF8 to ASCII** (with XML-escaped Unicode codepoints) for the encoding of the OCR
    files. This requires less CPU during decoding, since we don't have to take multi-byte sequences into
    account. To signal to the plugin that a given source path is encoded in ASCII, include the `{ascii}`
    string after the path, e.g. `/mnt/data/ocrdoc.xml{ascii}[31337:41337]`.

    For even more advice on performance tuning, refer to the [corresponding documentation section](./performance.md).

The structure of the source pointers depends on how your actual OCR files on disk map to documents in the Solr
index.

!!! caution "Encoding"
    The files pointed at by the source pointers **need to be UTF-8 or ASCII encoded**. Other encodings will lead
    to unexpected errors and weird behaviour, so make sure the files are in the correct encoding before you
    index them.

## One file per Solr document (`1:1`)

This is the simplest case: The contents of the OCR field in a Solr document correspond exactly to the contents
of a single OCR file on disk. The source pointer is simply **the path to the file**:

```json
POST http://solrhost:8983/solr/corename/update
{
    "id": "ocrdoc-1",
    "ocr_text": "/mnt/data/ocrdoc-1.xml"
}
```

That's it, during indexing and highlighting Solr will use the OCR file at `/mnt/data/ocrdoc-1.xml` to get the
text for indexing and highlighting.

## Multiple files per Solr document (`n:1`)

Frequently the OCR text for a single document (e.g. a book) is stored in one file per page in the document.
So, if we want our search results to be these documents and not individual pages from them, we need to
instruct the plugin to load the OCR content from multiple files.

In this case, the source pointer is the **list of all file paths, joined with the `+` character**:

```json
POST http://solrhost:8983/solr/corename/update
{
    "id": "ocrdoc-1",
    "ocr_text": "/mnt/data/ocrdoc-1_1.xml+/mnt/data/ocrdoc-1_2.xml+/mnt/data/ocrdoc-1_3.xml
}
```

For indexing and highlighting, Solr will load the contents of the `ocrdoc-1_1.xml`, `ocrdoc-1_2.xml` and 
`ocrdoc-1_2.xml` as a single continuous text.

## Advanced: One or more *partial* files per Solr document

A more complicated situation arises if the Solr documents need to refer to *parts* of one or more files on
disk. This happens for example when you have scans of bound newspaper volumes, which frequently consist
of more than 1000 pages. For search purposes, you want to map *single articles* from issues in this newspaper
volume to single Solr documents.

If the volume OCR is stored as one file per page, these articles can span multiple files, often in a
non-contiguous manner, so you need to refer to *regions* of these files.

For these cases, the source pointer can include **one or more byte regions per file**:

```json
POST http://solrhost:8983/solr/corename/update
[
  {
    "id": "article_1863-03-14_8",
    "title": "Contiguous article on a single page",
    "ocr_text": "/mnt/data/1863_185[216343:331347]"
  },
  {
    "id": "article_1863-03-15_12",
    "title": "Article spanning from the end of one file to the first part of a second file",
    "ocr_text": "/mnt/data/1863_187.xml[76306:]+/mnt/data/1863_188.xml[:196896]"
  },
  {
    "id": "article_1863-03-16_2",
    "title": "Article split between two pages, with the content on the first page split by an advertisement.",
    "ocr_text": "/mnt/data/1863_191.xml[1578:8937,12478:17621]+/mnt/data/1863_192.xml[837:28432]"
  }
]
```

As before, we concatenate multiple file paths with the `+` character. The source regions for each file are
listed as **comma-separated byte-regions** inside of square brackets.

The format of the regions is inspired by [Python's slicing syntax](https://docs.python.org/3/reference/expressions.html#slicings) and can take these forms:

- `start:` → Everything from byte offset `start` to the end of the file
- `start:end` → Everything between the byte offsets `start` (inclusive) and `end` (exclusive)
- `:end` → Everything from the start of the file to byte offset `end` (exclusive)

!!! caution "Region Requirements"
    - The concatenated content of your regions must be a half-way valid XML structure. While we
      tolerate *unclosed tags or unmatched closing tags* (they often can't be avoided), other
      errors such as partial tags (i.e. a missing `<` or `>`) will lead  to an error during indexing.
    - To get correct page numbers in your responses, make sure that you include any and all page
      openings for your content in the set of regions. For example, if your document is an article
      that spans from the bottom of one page to the top of the next, you will have to include a region
      for the opening element of the first page so we can determine the page for the first part of the
      article during highlighting


!!! caution "Byte Offsets"
    The region offsets are expected as **byte offsets**. Take care that the start and end of each region
    fall on the start of a valid unicode byte sequence, and not in the middle of a multi-byte sequence.
    Care needs to be taken when determining the offsets, since obtaining byte offsets for UTF8-encoded
    text files is difficult in some programming languages (most notoriously Java, use the
    [`net.byteseek:byteseek`](https://github.com/nishihatapalmer/byteseek) package)


!!! note "Example Implementation"
    The [example setup on GitHub](https://github.com/dbmdz/solr-ocrhighlighting/tree/master/example)
    uses a [Python script](https://github.com/dbmdz/solr-ocrhighlighting/blob/master/example/ingest.py)
    to index articles from multi-page newspaper scans into Solr. It works by [first extracting the OCR
    block ids for each article from a METS file](https://github.com/dbmdz/solr-ocrhighlighting/blob/master/example/ingest.py#L141-L147)
    and then [finds the byte regions these OCR blocks are located in](https://github.com/dbmdz/solr-ocrhighlighting/blob/master/example/ingest.py#L108-L123)
    to build the source pointer for each article.
