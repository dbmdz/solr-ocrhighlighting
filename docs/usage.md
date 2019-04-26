The plugin implements a number of usage scenarios, based on where and how the OCR documents are stored.
In the easiest case, the OCR documents are directly stored in Solr. This does not require any special configuration.

If this is not practical (e.g. because of index size), the plugin can also access OCR documents stored outside of Solr
for highlighting. However, this comes with some requirements as to the encoding. Generally speaking, **if your environment
allows for a slight modification of your OCR documents, the last scenario (ASCII + escaped Unicode codepoints) is highly
recommended.** It offers the most flexibility with the lowest index, memory and storage requirements. Refer to the
sections below for more information.

The following tables lists various features and drawbacks of the available scenarios:

| **Location of OCR content** | **Index Size**        | **Memory Usage** | **Easy to index**           | **Supports `hl.weightMatches`** | **Use UTF-8 files on disk** |
|-----------------------------|-----------------------|------------------|-----------------------------|---------------------------------|-----------------------------|
| Solr (stored field)         |   |       |  ✅ raw OCR                 | ✅                              | ❌                          |
| External (ASCII+XML-Escapes)| ✅ Just offsets       | ✅ Low           |  ✅ raw OCR                 | ✅                              | ✅ [3]                  |
| External (UTF-8 file)       | ✅ Just payloads      | ✅ Low           | ✅ words w/ byte offsets[2] | ❌ Only "classic" highlighting  | ✅                          |

**[1]** The whole document needs to be kept in memory during highlighting<br>
**[2]** Java implementations and a cross-platform CLI tool to generate the required format for all supported OCR formats are
        provided<br>
**[3]** Only technically UTF-8, since all non-ASCII codepoints have to be XML-escaped (i.e. `&#x17F` instead of `ſ`.
        However, all XML-processors should be able to work with these files just the same as if they were UTF-8 encoded, so
        it's as practical (and almost as space-efficient) as the UTF8 + byte offsets scenario, with the advantage that a
        more modern highlighting technique (`hl.weightMatches`) can be used.<br>


## Storage in stored fields

<dl>
<dt>Impact on index size</dt>
<dd>❌ <strong>High</strong>: Requires raw OCR documents to be stored in the Solr index</dd>
<dt>Impact on memory usage at query time</dt>
<dd>❌ <strong>High</strong>: The whole document needs to be kept in memory during highlighting</dd>
</dl>


## Storage outside of Solr

<dl>
<dt>Impact on index size</dt>
<dd>✅ <strong>Low</strong>: Solr only <em>indexes</em> the documents, but does not <em>store</em> them.</dd>
<dt>Impact on memory usage at query time</dt>
<dd>✅ <strong>Low</strong>: Only keeps in memory the parts that are actually required for highlighting</dt>
</dl>



The above configuration will require having the OCR markup in a stored field in Solr. This is less than ideal
for large OCR documents that often are well above 100MiB in size. For this reason, this plugins adds support
for lazy-loading documents (and only the really required parts of those documents) at query time.
For this, configure the `FieldLoader` after you've told the plugin about your OCR fields:


```xml
<!-- The encoding has to match that of the files on disk, usually UTF-8 or ASCII, although UTF-16 (BE and LE) is
     also supported. -->
<fieldLoader class="org.mdz.search.solrocr.lucene.fieldloader.PathFieldLoader" encoding="ascii">
  <lst name="externalFields">
    <str name="ocr_text">/google1000/{id}/hOCR.html</str>
  </lst>
</fieldLoader>
```

The default `PathFieldLoader` takes a mapping of field names to a path pattern. In the pattern, you can refer
to arbitrary document fields in curly braces (`{id}`) to build the path to the OCR document. You can also use
a Python-like slicing syntax to only refer to parts of the field values (e.g. `{id[:10]}`, `{id[2:4]}`,
`{id[:-5]}`).

If your use case requires more sophisticated resolving, or you want to load the document contents from
sources other than the file system, the plugin provides an `ExternalFieldLoader` interface that you can
implement and just pass in the `class` parameter.


## Encoding: XML-Escaped ASCII

<dl>
<dt>Easy to index</dt>
<dd>✅ <strong>Yes</strong>: Users can directly post the raw OCR file content to Solr</dd>
<dt>Supports modern highlighting (i.e. <tt>hl.weightMatches</tt>)</dt>
<dd>✅ <strong>Yes</strong></dd>
<dt>Works with standard UTF8 files</dt>
<dd>❌ <strong>No:</strong> Requires ASCII encoding with XML-escaped unicode codepoints
</dl>

The documents that you want to ingest into your index **need to be ASCII-encoded, with Unicode codepoints being
XML-escaped**. For example, the value `Wachſtube` needs to be encoded as `Wach&#383;tube`. The easiest way to convert
your XML documents to this encoding is to use Python:

```python
with open('./mydocument.xml', 'rt') as fp:
    ocr_text = fp.read()
with open('./mydocument_escaped.xml', 'wb') as fp:
    fp.write(ocr_text.encode('ascii', 'xmlcharrefreplace'))
```

That's basically all there is to it: Just read your file into a string and pass the contents as the field value
during your `POST` to Solr.

## Encoding: UTF8

<dl>
<dt>Easy to index</dt>
<dd>❌ <strong>No:</strong> Needs byte offsets for every term during indexing, requires tooling to generate
<dt>Supports modern highlighting (i.e. <tt>hl.weightMatches</tt>)</dt>
<dd>❌ <strong>No:</strong> Only classical highlighting supported, e.g. span-matches will be highlighted as individual terms</dd>
<dt>Works with standard UTF8 files</dt>
<dd>✅ <strong>Yes</strong>
</dl>

If you can't or don't want to slightly modify the OCR documents on disk, you will have to take some more steps during
indexing and also can't use the modern highlighting approach enabled by `hl.weightMatches` parameter (which will be the
default in Solr 8).

This approach differs from the above in that it completely bypasses Solr's approach to storing (character) offsets in
the index and instead stores the **byte offsets** in the term payload. These byte offsets need to be passed to Solr by
your application during indexing time, i.e. you cannot post OCR documents straight to Solr but need to convert them to
a special format beforehand.

First, for the schema configuration, you will have to enable term positions, term vectors and term payloads. To make
Solr store the payloads, make it tokenize on whitespace and split the payload from the document terms:

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

For getting your OCR documents into Solr, you need to pass the content in a special format that includes the byte offset
for each term:

```
Dieſe⚑5107923 leuchtenden⚑5108028 treuherzigen⚑5108138 blauen⚑5108250 Augen,⚑5108357
```

To convert from the supported formats (hOCR, ALTO and MiniOCR) to this format, we provide Java classes ([hOCR](TODO),
[ALTO](TODO) and and a small portable command-line utility (available from the
[GitHub Releases Page](https://github.com/dbmdz/solr-ocrhighlighting/releases)).

