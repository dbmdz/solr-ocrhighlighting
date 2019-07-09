# Solr OCR Highlighting

![Highlighted OCR snippet](img/snippet.png)

This Solr plugin lets you put word-level OCR text into one or more of you documents'
fields and then allows you to obtain structured highlighting data with the text
and its position on the page at query time:

```json
{
  "text": "to those parts, subject to unreasonable claims from the pro­prietor "
          "of Maryland, until the year 17C2, when the whole controversy was "
          "settled by Charles <em>Mason and Jeremiah Dixon</em>, upon their "
          "return from an observation of the tran­sit of Venus, at the Cape of "
          "Good Hope, where they",
  "score":5555104.5,
  "regions":[
    { "ulx":196, "uly":1703, "lrx":1232, "lry":1968, "page":"page_380" }
  ],
  "highlights":[
    [{ "text":"Mason and Jeremiah", "ulx":675, "uly":110, "lrx":1036, "lry":145,
       "page":"page_380"},
     { "text":"Dixon,", "ulx":1, "uly":167, "lrx":119, "lry":204, "page":"page_380"}]
  ]
}
```

All this can optionally be done without having to store any OCR text in the
index itself: The plugin can lazy-load only the parts required for highlighting
at query time from your original OCR input documents. 

It works by extending Solr's standard `UnifiedHighlighter` with support for
loading external field values and determining OCR positions from those field
values. This means that all options and query types supported by the
`UnifiedHighlighter` are also supported for OCR highlighting. The plugin also
works transparently with non-OCR fields and just lets the default
implementation handle those.

The plugin **works with all Solr versions >= 7.x** (tested with 7.6, 7.7 and 8.0).

## Features
- Index various OCR formats without little to no preprocessing
    * [hOCR](formats.md#hocr)
    * [ALTO](formats.md#alto)
    * [MiniOCR](formats.md#miniocr)
- Retrieve all the information needed to render a highlighted snippet view
  directly from Solr, without postprocessing
- Keep your index size manageable by re-using OCR documents on disk for
  highlighting

## Getting Started

To get started, refer to the [Getting Started documentation](getting_started.md).
