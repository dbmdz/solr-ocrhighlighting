The repository includes a full-fledged example setup based on the [Google
Books 1000](http://yaroslavvb.blogspot.com/2011/11/google1000-dataset_09.html)
and the [BNL L'Union Newspaper](https://data.bnl.lu/data/historical-newspapers/) datasets.
The Google Books dataset consists of 1000 Volumes along with their OCRed text
in the hOCR format and all book pages as full resolution JPEG images.
The BNL dataset consists of 2712 newspaper issues in the ALTO format and all
pages as high resolution TIF images.

The example ships with a search interface that allows querying the OCRed texts and displays
the matching passages as highlighted image and text snippets. Also included
is a small IIIF-Viewer that allows viewing the documents and searching for
text within them.

## Online version

A public instance of this example is available at [https://ocrhl.jbaiter.de](https://ocrhl.jbaiter.de).

The Solr server can be queried at `https://ocrhl.jbaiter.de/solr/ocr/select`, e.g. for
[`q="mason dixon"~10"`](https://ocrhl.jbaiter.de/solr/ocr/select?df=ocr_text&hl.ocr.fl=ocr_text&hl.snippets=10&hl.weightMatches=true&hl=on&q=%22mason+dixon%22%7E10)


## Prerequisites

To run the example setup yourself, you will need:

- Docker and `docker-compose`
- Python 3
- ~15GiB of free storage

## Running the example

1. `cd example`
2. `docker-compose up -d`
3. `./ingest.py`
4. Access `http://localhost:8181` in your browser

## Search Frontend

[![Search Frontend](img/example_search.png)](https://ocrhl.jbaiter.de)

## IIIF Content Search

[![IIIF Viewer with Content Search](img/example_iiifsearch.png)](https://ocrhl.jbaiter.de/viewer/?manifest=https://ocrhl.jbaiter.de/iiif/presentation/gbooks:0723/manifest&cv=page_8&q=Pennsylvania)

## Solr Configuration Walkthrough

[`solrconfig.xml`](https://github.com/dbmdz/solr-ocrhighlighting/blob/master/example/solr/cores/ocr/conf/solrconfig.xml)
```xml
<config>
  <luceneMatchVersion>7.6</luceneMatchVersion>
  <directoryFactory name="DirectoryFactory" class="${solr.directoryFactory:solr.StandardDirectoryFactory}"/>
  <schemaFactory class="ClassicIndexSchemaFactory"/>

  <!-- Load the plugin JAR from the contrib directory -->
  <lib dir="../../../contrib/ocrsearch/lib" regex=".*\.jar" />

  <!-- Define a search component that takes care of OCR highlighting -->
  <searchComponent class="de.digitalcollections.solrocr.solr.OcrHighlightComponent"
                   name="ocrHighlight" />

  <!-- Add the OCR Highlighting component to the request handler -->
  <requestHandler name="/select" class="solr.SearchHandler">
    <arr name="components">
      <str>query</str>
      <!--
        Note that the OCR highlighting component comes **before**
        the default highlighting component!
      -->
      <str>ocrHighlight</str>
      <str>highlight</str>
    </arr>
  </requestHandler>
</config>
```

[`schema.xml`](https://github.com/dbmdz/solr-ocrhighlighting/blob/master/example/solr/cores/ocr/conf/schema.xml)
```xml
<fieldtype name="text_ocr" class="solr.TextField" storeOffsetsWithPositions="true" termVectors="true">
  <analyzer type="index">
    <charFilter class="de.digitalcollections.solrocr.lucene.filters.ExternalUtf8ContentFilterFactory" />
    <charFilter class="de.digitalcollections.solrocr.lucene.filters.OcrCharFilterFactory" />
    <tokenizer class="solr.StandardTokenizerFactory"/>
    <filter class="solr.LowerCaseFilterFactory"/>
    <filter class="solr.StopFilterFactory"/>
    <filter class="solr.PorterStemFilterFactory"/>
  </analyzer>
  <analyzer type="query">
    <tokenizer class="solr.StandardTokenizerFactory"/>
    <filter class="solr.LowerCaseFilterFactory"/>
    <filter class="solr.StopFilterFactory"/>
    <filter class="solr.PorterStemFilterFactory"/>
  </analyzer>
</fieldtype>
```