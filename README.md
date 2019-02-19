# solr-ocr-plugin

This Solr plugin lets you put OCR text into one or more of you documents'
fields and then allows you to obtain structured highlighting data with the text
and its position on the page at query time. All this without having to store
the OCR data in the index itself, but at arbitrary external locations instead.

It works by extending Solr's standard `UnifiedHighlighter` with support for
loading external field values and determining OCR positions from those field
values. This means that all options and query types supported by the
`UnifiedHighlighter` are also supported for OCR highlighting. The plugin also
works transparently with non-OCR fields and just lets the default
implementation handle those.

The plugin implements a number of usage scenarios, based on where and how the
OCR documents are stored:

| **Supported Formats** | **Location of OCR content** | **Index Size**        | **Memory Usage** | **Easy to index**           | **Supports `hl.weightMatches`** | **Use UTF-8 files on disk** |
|-----------------------|-----------------------------|-----------------------|------------------|-----------------------------|---------------------------------|-----------------------------|
| hOCR/MiniOCR          | Solr (stored field)         | ❌ Raw docs in index  | ❌ High [1]      |  ✅ raw OCR                 | ✅                              | ❌                          |
| hOCR/MiniOCR          | External (UTF-16 file)      | ✅ Just offsets       | ✅ Low           |  ✅ raw OCR                 | ✅                              | ❌ [2]                      |
| hOCR/ALTO/MiniOCR     | External (UTF-8 file)       | ✅ Just payloads      | ✅ Low           | ✅ words w/ byte offsets[3] | ❌ Only "classic" highlighting  | ✅                          |
| hOCR/MiniOCR [4]      | External (ASCII+XML-Escapes)| ✅ Just offsets       | ✅ Low           |  ✅ raw OCR                 | ✅                              | ✅ [5]                      |

[1] The whole document needs to be kept in memory during highlighting

[2] This option is really bad storage-wise, since the documents will be up to twice the size of their UTF-8 encoded
    variants.

[3] Java implementations and a cross-platform CLI tool to generate the required format for all supported OCR formats are
    provided
    
[4] Support for ALTO should be possible with some trickery, but is not implemented yet.
    
[5] Only technically UTF-8, since all non-ASCII codepoints have to be XML-escaped (i.e. `&#x17F` instead of `ſ`.
    However, all XML-processors should be able to work with these files just the same as if they were UTF-8 encoded, so
    it's as practical (and almost as space-efficient) as the UTF8 + byte offsets scenario, with the advantage that a
    more modern highlighting technique (`hl.weightMatches`) can be used.

**TODO**: Badges

**TODO**: Graphic that shows the general principle

## Installation

- Download plugin jar
- Drop into `core/lib/` directory

# Solr Configuration

Using the plugin requires some configuration in your `solrconfig.xml`. For one, you have to tell Solr's
highlighting component to use the OCR Highlighter for your OCR format (hOCR, ALTO or MiniOCR).
Also, you will have to specify which of your fields contain OCR text (i.e. the `solrconfig` is tied to
the schema):

```xml
<config>
  <searchComponent class="solr.HighlightComponent" name="highlight">
    <!-- Use this plugin's custom highlighter with hOCR.
    
    For ALTO, specify `org.mdz.search.solrocr.formats.alto.AltoFormat` int the `ocrFormat`
    attribute, for MiniOCR use `org.mdz.search.solrocr.formats.miniocr.MiniOcrFormat`. -->
    <highlighting class="org.mdz.search.solrocr.solr.SolrOcrHighlighter"
                  ocrFormat="org.mdz.search.solrocr.formats.hocr.HocrFormat">
        <!-- Names of the fields in the schema that contain OCR text. -->
        <lst name="ocrFields">
          <str>ocr_text</str>
        </lst>
      </highlighting>
  </searchComponent>
</config>
```

## External fields
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


## Schema

The OCR highlighter has some requirements for the fields it is supposed to highlight. The details vary depending on
the usage scenario.

### Stored field and ASCII-encoded markup with escaped Unicode codepoints
```xml
<!-- Storing the offsets is mandatory with this approach. Term Vectors are optionally, but help speed up highlighting
     wildcard queries. -->
<fieldtype name="text_ocr" class="solr.TextField" storeOffsetsWithPositions="true" termVectors="true">
  <analyzer>
    <!-- Strip away the XML tags to arrive at a plaintext version of the OCR -->
    <charFilter class="solr.HTMLStripCharFilterFactory" />
    <!-- rest of your analyzer chain -->
    <!-- ..... -->
  </analyzer>
</fieldtype>
```    

### UTF-8 encoded markup
```xml
<!-- Positions, Term Vectors and Term Payloads are mandatory with this approach. -->
<fieldtype name="text_ocr" class="solr.TextField" termPositions="true" termVectors="true" termPayloads="true">
  <analyzer>
    <!-- Mandatory, the input is a whitespace-separated sequence of {term}{delimiter}{offset} units and has to be
         split on the whitespace -->
    <tokenizer class="solr.WhitespaceTokenizerFactory"/>
    <!-- The delimiter can be any UTF-16 codepoint, "⚑" is used by the provided Java implementation and CLI tool -->
    <filter class="solr.DelimitedPayloadTokenFilterFactory" delimiter="⚑"
            encoder="org.mdz.search.solrocr.lucene.byteoffset.ByteOffsetEncoder" />
    <!-- rest of your analyzer chain -->
    <!-- ..... -->
  </analyzer>
</fieldtype>
```

## Usage

- Converting OCR format to indexing format (terms + byte offsets), if neccessary
- Ingesting a document with an OCR field
- Querying with highlighting, example result

## The MiniOCR format

- Why????
- Elements, Structure
- floats or ints for coordinates
- Hyphenation with `&shy;`
- Encoding: Stripping all HTML tags should result in a readable plaintext document

## Known Issues

- File size is limited to 2GiB
- The `hl.weightMatches` parameter is not supported when using external UTF-8
  files, i.e. it will be ignored and the classical highlighting approach will
  be used instead.


## Contributing

Found a bug? Want a new feature? Make a fork, create a pull request.

For larger changes/features, it's usually wise to open an issue before starting
the work, so we can discuss if it's a fit.

## Support us!

We always appreciate if users let us know how they're using our software and
libraries. It helps us to focus our efforts on our open source offerings, so we
can create even more useful stuff for the community.

So don't hesitate to drop us a line at
[TODO@bsb-muenchen.de](mailto:TODO@bsb-muenchen.de) if you could make use of
the plugin :-)

## License

[Apache License 2.0](https://github.com/dbmdz/solr-ocr-plugin/blob/master/LICENSE)
