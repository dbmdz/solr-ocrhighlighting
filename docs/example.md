The repository includes a full-fledged example setup based on the [Google
Books 1000 Dataset](http://yaroslavvb.blogspot.com/2011/11/google1000-dataset_09.html).
It consists of 1000 Volumes along with their OCRed text in the hOCR format
and all book pages as full resolution JPEG images. The example ships with a
search interface that allows querying the OCRed texts and displays the
matching passages as highlighted image and text snippets. Also included is a
small IIIF-Viewer that allows viewing the complete volumes and searching for
text within them.

## Online version

A public instance of this example is available at https://ocrhl.jbaiter.de.

The Solr server can be queried at `https://ocrhl.jbaiter.de/solr`, e.g.
[`q="mason dixon"~10"](https://ocrhl.jbaiter.de/solr/ocrtest/select?df=ocr_text&hl.fl=ocr_text&hl.snippets=10&hl.weightMatches=true&hl=on&q=%22mason+dixon%22%7E10)


## Prerequisites

To run the example setup yourself, you will need:

- Docker and `docker-compose`
- Python 3
- ~8GiB of free storage

## Running the example

1. `docker-compose up -d`
2. `./ingest_google100.py`
3. Access `http://localhost:8181` in your browser

## Search Frontend

![Search Frontend](img/example_search.png)

## IIIF Content Search

![IIIF Viewer with Content Search](img/example_iiifsearch.png)

## Solr Configuration Walkthrough

[`solrconfig.xml`](https://github.com/dbmdz/solr-ocrhighlighting/blob/master/example/solr/ocrtest/conf/solrconfig.xml)
```xml

<!-- The Google 1000 books corpus used for the example is in the hOCR format -->
<searchComponent class="org.mdz.search.solrocr.solr.HighlightComponent" name="ocrHighlight"
                 ocrFormat="org.mdz.search.solrocr.formats.hocr.HocrFormat">
  <!-- We have a single field that contains OCR -->
  <lst name="ocrFields">
    <str>ocr_text</str>
  </lst>
  <!-- The example setup loads the ASCII-encoded OCR documents from local storage -->
  <fieldLoader class="org.mdz.search.solrocr.lucene.fieldloader.PathFieldLoader" encoding="ascii">
    <lst name="externalFields">
      <!-- E.g. /google1000/Volume_0000.hocr -->
      <str name="ocr_text">/google1000/{id}.hocr</str>
    </lst>
  </fieldLoader>
</searchComponent>
```

[`schema.xml`](https://github.com/dbmdz/solr-ocrhighlighting/blob/master/example/solr/ocrtest/conf/schema.xml)
```xml
<fieldtype name="text_ocr" class="solr.TextField" storeOffsetsWithPositions="true" termVectors="true">
  <analyzer>
    <charFilter class="solr.HTMLStripCharFilterFactory" />
    <tokenizer class="solr.StandardTokenizerFactory"/>
    <filter class="solr.LowerCaseFilterFactory"/>
    <filter class="solr.StopFilterFactory"/>
    <filter class="solr.PorterStemFilterFactory"/>
  </analyzer>
</fieldtype>
```