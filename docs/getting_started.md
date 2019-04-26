This guide will walk you through setting up the plugin for highlighting OCR documents in the hOCR format.

## Requirements

- Solr >= 7.x
- OCR documents need to be in hOCR, ALTO or [MiniOCR](formats.md#miniocr) formats.<br>
  If you are [storing your OCR documents outside of the index](usage.md#storage-outside-of-solr), the plugin assumes one file per Solr document, although there is a [workaround for this](faq.md#can-i-have-documents-that-point-to-a-part-of-an-ocr-document-on-external-storage).<br>
  In any case, the OCR documents need at least page, line and word-level segmentations to work with the plugin.

## Installation
Download the JAR for the latest release from the [GitHub Releases website](https://github.com/dbmdz/solr-ocrhighlighting/releases) and drop it into your core's `lib` directory.

## Configuration

For your schema, you will have to define a type that enables the storage of offsets and positions. Enabling term
 vectors is optional, although it significantly highlights wildcard queries.
The indexing analyzer chain for the field type needs to start with the `HTMLStripCharFilterFactory`.
```xml
<!-- Storing the offsets is mandatory with this approach. Term Vectors are optionall, but help speed up highlighting
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
      <!-- This value needs to be equal to the `name` attribute on the searchComponent -->
      <str>ocrHighlight</str> 
    </arr>
  </requestHandler>
</config>
```

This configuration will **store the OCR documents in the Solr index itself**. If you want to store them outside of
the index on the file system, refer to the [corresponding section on the Usage Scenarios page](usage.md#storage-outside-of-solr). For formats other than hOCR, refer to the corresponding section in the [OCR Formats page](formats.md).

After you've saved your configuration and schema and restarted Solr, you can verify that the plugin is activated by
checking the "Plugin" section in the Solr admin interface for your core, there should no be a Highlighting plugin with
the `name` you chose:

![](img/config-plugin-enabled.png)

## Indexing

Indexing for most usage scenarios (except for externally stored UTF8 files, [see here](usage.md)) is simple: Just POST
the raw content of the OCR document in your document field. Here's an example using [httpie](https://httpie.org)
- Simple curl command-line to index the documents
- Refer to [Usage Scenarios](usage.md) for byte offset variant

## Querying
At query time, no special parameters besides `hl=true` and an inclusion of your OCR fields in the `hl.fields` parameter
are required. The result will look like this (with the XML output format):

**TODO:** JSON format and from Google1000 dataset
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
  `block` or `page` and defaults to `block`.
- `hl.ocr.pageId`: Only show passages from the page with this identifier. Useful if you want to implement a
  "Search on this page" feature (e.g. for the [IIIF Content Search API](https://iiif.io/api/search/1.0/)).
- `hl.ocr.absoluteHighlights`: Return the coordinates of highlighted regions as absolute coordinates (i.e. relative to
  the page, not the snippet region)
