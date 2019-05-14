In the setup described in the [Getting Started guide](getting_started.md), the OCR documents are
directly stored in Solr. This does not require any special configuration besides setting `stored=true`
on the fields in the schema.

However, this approach has several drawbacks, that might not make it practical for your use case:

- **Index size:** Raw OCR documents need to be stored in the Solr index, depending on the format
  these regularly reach sizes  above 100MiB, which adds up really quickly with thousands of documents
- **Memory Usage:** During highlighting, Solr will load the full contents of the stored field into memory.
  Here again, the document sizes involved can make this problematic.


## Storing OCR outside of Solr

To work around the above issues, the plugin can also access OCR documents stored outside of Solr
for highlighting. This works by lazily loading only the parts of the documents that actually
need to be highlighted at query time from an external source like the file system.

This works, since with an appropriately configured index, Solr stores the *character offsets* of
the indexed terms in the input document in the index. Given these offsets, we can seek into
the input document and retrieve just the parts that are needed.

There is, however, one complication: Internally, Solr works with UTF-16 strings, where every
character takes up exactly 2 bytes, which is also what the stored character offsets are based on.
In a UTF-16 encoded document, given a character offset of `32`, you can obtain the character at this
position by simply seeking into the input document at position `64` (two bytes per character) and
reading the two bytes that make up the character

This would easy, but almost nobody stores documents in UTF-16, since it is very space-inefficient.
Most real-world documents will be stored in UTF-8, which is a *variable length encoding*, meaning
that the number of bytes a character takes up depends on the character itself (e.g. `a` is one byte,
while `ä` is two bytes). This makes it impossible to calculate the byte offset of a given character,
given only the character offset.

To get around this limitation, you have two options:

1. [Encode the input documents in ASCII](#ascii) (= always one byte per character) and
   format non-ASCII codepoints as XML character entities
   (i.e. `ſ` becomes `&#383;`)
2. [Leave the input documents in UTF-8](#utf8) and store the byte offset for
   every term in the Solr index

**Option #1 is the recommended way**: It only requires a slight modification to
your input documents with no effects on other consumers and offer the best
experience.

## Document resolving

To enable accessing external documents during highlighting, you will have to configure
a `FieldLoader` in the `<searchComponent>` section of your `solrconfig.xml`.
For documents on the local filesystem, the loader to use is
`org.mdz.search.solrocr.lucene.fieldloader.PathFieldLoader`.

```xml
<searchComponent class="org.mdz.search.solrocr.solr.HighlightComponent" name="highlight"
                 ocrFormat="org.mdz.search.solrocr.formats.alto.AltoFormat">
  <lst name="ocrFields">
    <str>ocr_text</str>
  </lst>
  <fieldLoader class="org.mdz.search.solrocr.lucene.fieldloader.PathFieldLoader" encoding="ascii">
    <lst name="externalFields">
      <str name="ocr_text">/google1000/{id}/hOCR.html</str>
    </lst>
  </fieldLoader>
</searchComponent>
```

To tell the loader where to find the file for a given document, you have to provide it
with the encoding of the files ([`ascii`](#ascii) or [`utf8`](#utf8)) and a mapping from the
field name to a **path pattern**.

The path pattern can include arbitrary field names enclosed in curly braces. During resolving,
these will be replaced with the contents of the field for the current document. The OCR document
will then be loaded from the path described by the resulting string. For example,
given a document where the `id` field is set to `1337`, the pattern `/data/ocr/{id}_ocr.xml` would
load the OCR document from `/data/ocr/1337_ocr.xml`. You can use any field that is defined in your
schema inside of the pattern. You can also use a Python-like slicing syntax to only refer to
parts of the field values (e.g. `{id[:10]}`, `{id[2:4]}`, `{id[:-5]}`).

!!! caution
    When using external documents for highlighting, the performance depends almost exclusively on
    how fast the underlying storage is able to perform random I/O. This is why **using flash storage
    for the documents is highly recommended**.

!!! note
    If your use case requires more sophisticated resolving, or you want to load the document contents from
    sources other than the file system, the plugin provides an
    [`ExternalFieldLoader`](https://github.com/dbmdz/solr-ocrhighlighting/blob/master/src/main/java/org/mdz/search/solrocr/lucene/fieldloader/ExternalFieldLoader.java)
    interface that you can implement and just pass in the `class` parameter.
    
    
## Indexing multiple files as a single document

In some environments, OCR documents are stored with one file per page in the OCRed document. The plugin supports
indexing these files **as a single document** if some pre-conditions are met:

- The lexicographical order of the file paths that make up the pages of a document is equal to the order of those pages
  in the document
- The files are **concatenated in the same order without any characters in between** at indexing time

If these conditions are met, add the `multiple="true"` option to your field loader configuration and a wildcard
to your path patterns (e.g. `/local/ocr/{id}/*.xml`). At indexing time, instead of POSTing the contents of a single
file in your document's OCR field, you submit the **concatenated contents of all files**.

Refer to the [unit test](https://github.com/dbmdz/solr-ocrhighlighting/blob/master/src/test/java/org/mdz/search/solrocr/solr/AltoMultiTest.java)
to see an example setup for this use case.


## Encoding: ASCII with XML-Escapes {: #ascii}

To use ASCII-encoded documents, the only thing you have to do is pass `ascii` in the `encoding` parameter
of your `fieldLoader` and make sure that they **do not contain any unescaped UTF-8 codepoints**.
This can be done very effectively with this little Python snippet:

```python
utftext.encode('ascii', 'xmlcharrefreplace')
```

The repository also includes a [script that you can use to do the conversion:](https://github.com/dbmdz/solr-ocrhighlighting/raw/master/ascii_escape)

```sh
$ cat input.xml | ./ascii_escape > output.xml

#or:
$ ./ascii_escape input.xml output.xml
```

## Encoding: UTF-8 {: #utf8}

!!! caution
    This approach comes with significant drawbacks:

    - **You cannot use the modern highlighting approach** (`hl.weightMatches=true`, default in Solr >=8)
    - Your index will be **significantly larger** than with the ASCII-approach.

In order for the plugin to know which parts of the input files to read during highlighting, you need to
tell it the *byte offset* for every token in the input document. Solr will then store this offset in the
*term payloads* and the plugin will load it from there using highlighting.

This approach won't allow directly indexing the OCR documents, you will have to convert them to a special
format that includes the byte offset for every token, e.g.
`Dieſe⚑5107923 leuchtenden⚑5108028 treuherzigen⚑5108138 blauen⚑5108250 Augen,⚑5108357`.

The `⚑` character is the default delimiter that separates the term from its offset, but you can pick your
own value with the included tool and the `delimiter` attribute in the configuration below.

!!! note
    This format will only be used for indexing, you don't need to store it on disk. For highlighting,
    your unmodified OCR documents will be used.

You don't have to do this by yourself, we provide a Java implementation for every supported format
([hOCR](https://github.com/dbmdz/solr-ocrhighlighting/blob/master/src/main/java/org/mdz/search/solrocr/formats/hocr/HocrByteOffsetsParser.java),
[ALTO](https://github.com/dbmdz/solr-ocrhighlighting/blob/master/src/main/java/org/mdz/search/solrocr/formats/alto/AltoByteOffsetsParser.java)
and [MiniOCR](https://github.com/dbmdz/solr-ocrhighlighting/blob/master/src/main/java/org/mdz/search/solrocr/formats/mini/MiniOcrByteOffsetsParser.java))
as well as a cross-platform command-line tool (available on the
[GitHub releases page](https://github.com/dbmdz/solr-ocrhighlighting/releases)):

```sh
# The tool works transparently with all supported formats
./offsets-parser ../example/google1000/Volume_0000.hocr > out.txt
```

In your schema, you will have to enable term positions, term vectors and term payloads.
In your indexing analyzer chain, you need to remove the `HTMLStripCharFilterFactory`
(and the `AltoCharFilterFactory` if using ALTO) and instead tell Solr to
tokenize on whitespace, split off those offsets during indexing and store them as payloads.

```xml
<!-- Positions, Term Vectors and Term Payloads are mandatory with this approach. -->
<fieldtype name="text_ocr" class="solr.TextField" termPositions="true" termVectors="true" termPayloads="true">
  <analyzer>
    <!-- Mandatory, the input is a whitespace-separated sequence of {term}{delimiter}{offset} units and has to be
         split on whitespace -->
    <tokenizer class="solr.WhitespaceTokenizerFactory"/>
    <!-- The delimiter can be any UTF-16 codepoint, "⚑" is used by default in the provided Java implementation and CLI tool -->
    <filter class="solr.DelimitedPayloadTokenFilterFactory" delimiter="⚑"
            encoder="org.mdz.search.solrocr.lucene.byteoffset.ByteOffsetEncoder" />
    <!-- rest of your analyzer chain -->
    <!-- ..... -->
  </analyzer>
</fieldtype>
```