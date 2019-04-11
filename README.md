![](https://i.imgur.com/5tt4mgZ.png)

# solr-ocrhighlighting

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

The plugin **works with all Solr versions >= 7.x** (tested with 7.6, 7.7 and 8.0).
It implements a number of usage scenarios,
based on where and how the OCR documents are stored.
**If your environment allows for a slight modification of your OCR documents,
the last scenario (ASCII + escaped Unicode codepoints) is highly recommended.**
It offers the most flexibility with the lowest index, memory and storage
requirements.

| **Location of OCR content** | **Index Size**        | **Memory Usage** | **Easy to index**           | **Supports `hl.weightMatches`** | **Use UTF-8 files on disk** |
|-----------------------------|-----------------------|------------------|-----------------------------|---------------------------------|-----------------------------|
| Solr (stored field)         | ❌ Raw docs in index  | ❌ High [1]      |  ✅ raw OCR                 | ✅                              | ❌                          |
| External (UTF-8 file)       | ✅ Just payloads      | ✅ Low           | ✅ words w/ byte offsets[2] | ❌ Only "classic" highlighting  | ✅                          |
| External (ASCII+XML-Escapes)| ✅ Just offsets       | ✅ Low           |  ✅ raw OCR                 | ✅                              | ✅ [3]                  |

**[1]** The whole document needs to be kept in memory during highlighting<br>
**[2]** Java implementations and a cross-platform CLI tool to generate the required format for all supported OCR formats are
        provided<br>
**[3]** Only technically UTF-8, since all non-ASCII codepoints have to be XML-escaped (i.e. `&#x17F` instead of `ſ`.
        However, all XML-processors should be able to work with these files just the same as if they were UTF-8 encoded, so
        it's as practical (and almost as space-efficient) as the UTF8 + byte offsets scenario, with the advantage that a
        more modern highlighting technique (`hl.weightMatches`) can be used.<br>

**TODO**: Badges


## Installation

- Download the latest JAR from the [GitHub Releases Page](https://github.com/dbmdz/solr-ocrhighlighting/releases)
- Drop the JAR into the `core/lib/` directory for your Solr core


## Running the example

The repository includes a full-fledged example setup based on the
[Google Books 1000 Dataset](http://yaroslavvb.blogspot.com/2011/11/google1000-dataset_09.html).
It consists of 1000 Volumes along with their OCRed text in the hOCR format and all book pages as
full resolution JPEG images. The example ships with a search interface that allows querying the
OCRed texts and displays the matching passages as highlighted image and text snippets.
Also included is a small IIIF-Viewer that allows viewing the complete volumes and searching for text within
them. Refer to the `README` in the `example` directory for instructions on how to run the example.

## Solr Configuration

Using the plugin requires some configuration in your `solrconfig.xml`. For one, you have to define a
search component to add the OCR highlighting information for your OCR format (hOCR, ALTO or MiniOCR)
to the response.
Also, you will have to specify which of your fields contain OCR text (i.e. the `solrconfig` is
currently tied to the schema):

```xml
<config>
    <!-- Use this plugin's custom highlighter with hOCR.

    For ALTO, specify `org.mdz.search.solrocr.formats.alto.AltoFormat` int the `ocrFormat`
    attribute, for MiniOCR use `org.mdz.search.solrocr.formats.miniocr.MiniOcrFormat`. -->
  <searchComponent class="org.mdz.search.solrocr.solr.HighlightComponent" name="ocrHighlight"
                   ocrFormat="org.mdz.search.solrocr.formats.hocr.HocrFormat">
      <!-- Names of the fields in the schema that contain OCR text. -->
      <lst name="ocrFields">
        <str>ocr_text</str>
      </lst>
  </searchComponent>

  <!-- Add the OCR highlighting component to the components on your request handler(s) -->
  <requestHandler name="/select" class="solr.SearchHandler">
    <arr name="components">
      <str>query</str>
      <str>highlight</str>
      <str>ocrHighlight</str>
    </arr>
  </requestHandler>

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


## Schema and indexing documents

The OCR highlighter has some requirements for the fields it is supposed to highlight and its contents. The details vary
depending on the usage scenario.

### Stored fields and ASCII-encoded markup with escaped Unicode codepoints
For your schema, you will have to enable the storage of offsets (term vectors are also recommended for speeding up
highlighting of wildcard queries) and add the `HTMLStripCharFilterFactory` to your analyzer chain.
If you're using ALTO OCR documents, you will also have to add the `AltoCharFilterFactory` as the first component in
your analyzer chain.
```xml
<!-- Storing the offsets is mandatory with this approach. Term Vectors are optionally, but help speed up highlighting
     wildcard queries. -->
<fieldtype name="text_ocr" class="solr.TextField" storeOffsetsWithPositions="true" termVectors="true">
  <analyzer>
    <!-- For ALTO, please add this as the first filter:
    <charFilter class="org.mdz.search.solrocr.formats.alto.AltoCharFilterFactory" /> -->
    <!-- Strip away the XML tags to arrive at a plaintext version of the OCR -->
    <charFilter class="solr.HTMLStripCharFilterFactory" />
    <!-- rest of your analyzer chain -->
    <!-- ..... -->
  </analyzer>
</fieldtype>
```

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

### UTF-8 encoded markup

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

## Highlighting Output for queries

At query time, no special parameters besides `hl=true` and an inclusion of your OCR fields in the `hl.fields` parameter
are required. The result will look like this (with the XML output format):

```xml
<response>
<result name="response" numFound="1" start="0">
  <doc>
    <str name="id">42</str></doc>
</result>
<lst name="ocrHighlighting">
  <lst name="42">
    <lst name="ocr_text">
      <arr name="snippets">
        <lst>
          <str name="page">page_107</str>
          <str name="text">und weder Liebe noch Zorn für fie übrig behalten, Jeden: falls prügelte er fie oft, wenn er kam, und niemals tönten ihr die &lt;em&gt;Volfslieder heller von den Lippen&lt;/em&gt;, als nach ſol&lt;h einem feſtlichen Wiederſehen. Viele früheſte Kindheitserinnerungen vorher und nach-</str>
          <float name="score">1242847.8</float>
          <lst name="region">
            <int name="ulx">126</int>
            <int name="uly">1572</int>
            <int name="lrx">1439</int>
            <int name="lry">1903</int>
          </lst>
          <arr name="highlights">
            <arr>
              <lst>
                <str name="text">Volfslieder heller von den Lippen</str>
                <int name="ulx">366</int>
                <int name="uly">139</int>
                <int name="lrx">1200</int>
                <int name="lry">193</int>
              </lst>
            </arr>
          </arr>
        </lst>
      </arr>
      <int name="numTotal">1</int>
    </lst>
  </lst>
</lst>
</response>
```

As you can see, the `ocrHighlighting` component contains for every field in every matching document a list of
passages that match the query. The passage lists the page the match occurred on, the matching text, the score of the
passage and the coordinates of the region on the page image containing the passage text. Additionally, it also includes
the region and text for every hit (i.e. the actual tokens that matched the query). Note that the region coordinates are
**relative to the containing region, not the page!**

You can customize the way the passages are formed. By default the passage will include two lines above and below the
line with the match. Passages will also not cross block boundaries (what this means concretely depends on the format).
These parameters can be changed at query time:

- `hl.ocr.contextBlock`: Select which block type should be considered for determining the context. Valid values are
  `word`, `line`, `paragraph`, `block` or `page` and defaults to `line`.
- `hl.ocr.contextSize`: Set the number of blocks above and below the matching block to be included in the passage.
  Defaults to `2`.
- `hl.ocr.limitBlock`: Set the block type that passages may not exceed. Valid values are `word`, `line`, `paragraph`,
  `block` or `page` and defaults to `page`.
- `hl.ocr.pageId`: Only show passages from the page with this identifier. Useful if you want to implement a
  "Search on this page" feature (e.g. for the [IIIF Content Search API](https://iiif.io/api/search/1.0/)).


## The MiniOCR format

This plugin includes support for a custom OCR format that we dubbed *MiniOCR*. This format is intended for use cases
where using the existing OCR files is not possible (e.g. because they're in an unsupported format or because you don't
want to ASCII-encode them and still use the modern highlighting approach). In these cases, minimizing the storage
requirements for the derived OCR files is important, which is why we defined this minimalistic format.

A basic example looks like this:

```xml
<p id="page_identifier">
  <b>
    <l><w x="50 50 100 100">A</w> <w x="150 50 100 100">Line</w></l>
  </b>
</p>
```

The format uses the following elements for describing the page structure:

- `<p>` for pages, can have a `id` attribute that contains the page identifier
- `<b>` for "blocks", takes no attributes
- `<l>` for lines, takes no attributes
- `<w>` for words, takes a `box` attribute that contains the x and y offset and the width and height of the word in
  pixels on the page image. The coordinates can be either integrals or floating point values between 0 and 1 (to denote
  relative coordinates).

## Known Issues

- The supported file size is limited to 2GiB, since Lucene uses 32 bit integers throughout for storing offsets
- The `hl.weightMatches` parameter is not supported when using external UTF-8 files, i.e. it will be ignored and the
  classical highlighting approach will be used instead.


## FAQ

### Can I have documents that point to a part of an OCR document on external storage?

**Yes, with an ugly hack**. This use case appears e.g. when indexing digital newspapers,
where you have a single large volume on disk (e.g. the OCR text for the bound volume containing all issues from the
year 1878) but you want your Solr documents to be more fine-grained (e.g. on the issue or even article level).
A problem in Solr is that the source of the offsets that are used for highlighting are always relative to the actual
document that was used for indexing and cannot be easily customized. To work around this:<br/>
**Replace all of the content preceding your sub-section with a single XML comment tag that is exactly as long as the
old content and discard all content that follows after the sub-section** (We told you the solution was hacky, didn't
we?). This will lead the analyzer chain to discard all of the undesired content, while still storing the correct offsets
for the sub-section content in the index.

Minimal example before masking:

```
<l>Some content that you don't want in your Solr document</l>
<l>Here's the content you want in the index for this document</l>
<l>And here's some extra content following it that you don't want</l>
```

Minimal example after masking:
```
<!---------------------------------------------------------->
<l>Here's the content you want in the index for this document</l>
```


## Contributing

Found a bug? Want a new feature? Make a fork, create a pull request.

For larger changes/features, it's usually wise to open an issue before starting
the work, so we can discuss if it's a fit.

## Support us!

We always appreciate if users let us know how they're using our software and
libraries. It helps us to focus our efforts on our open source offerings, so we
can create even more useful stuff for the community.

So don't hesitate to drop us a line at
[johannes.baiter@bsb-muenchen.de](mailto:johannes.baiter@bsb-muenchen.de) if you could make use of
the plugin :-)

## License

[MIT License](https://github.com/dbmdz/solr-ocr-plugin/blob/master/LICENSE)
