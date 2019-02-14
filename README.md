# solr-ocr-plugin

This Solr plugin lets you put OCR text into one or more of you documents' fields and then allows you to obtain structured highlighting data with the text and its position on the page at query time. All this without having to store the OCR data in the index itself, but at arbitrary external locations instead.

It works by extending Solr's standard `UnifiedHighlighter` with support for loading external field values and determining OCR positions from those field values. This means that (almost) all options and query types supported by the `UnifiedHighlighter` are also supported for OCR highlighting. The plugin also works transparently with non-OCR fields and just lets the default implementation handle those.

The plugin implements a number of usage scenarios:

1. Index raw OCR documents (hOCR, ALTO or MiniOCR) into Solr and stored them in stored field<br>
   **😊** Very simple to use, can use all regular features of the `UnifiedHighlighter`<br>
   **😒** Large index size, very memory-intensive with large documents
2. Index raw OCR documents into Solr and store them in UTF-16 encoded files in an external location.<br>
   **😊** Simple to index (just raw documents), small index size, memory-efficient, can use all regular features of the `UnifiedHighlighter`<br>
   **😒** Need to keep UTF-16 encoded OCR documents around on external storage (space-inefficient, probably need to be converted from UTF-8 first)
3. Store the OCR documents in UTF-8 encoded files on disk, index parsed OCR documents that contain the byte offsets of each word in the file into Solr<br>
   **😊** Small index size, memory-efficient, can use existing UTF-8 encoded files on external storage<br>
   **😒** Indexing is more complicated: Need to determine the byte offsets for each word in the OCR document (**but:** Java implementation provided for all supported OCR formats), cannot use modern `hl.weightMatches` highlighting Method


**TODO**: Badges

**TODO**: Graphic that shows the general principle

## Installation

- Download plugin jar
- Drop into `core/lib/` directory
- Show example `solrconfig.xml`

## Usage

- Converting OCR format to indexing format (terms + byte offsets)
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
- The `hl.weightMatches` parameter is not supported when using external UTF-8 files, i.e. it will be ignored and the classical highlighting approach will be used instead.


## Contributing

Found a bug? Want a new feature? Make a fork, create a pull request.

For larger changes/features, it's usually wise to open an issue before starting the work, so we can discuss if it's a fit.

## Support us!

We always appreciate if users let us know how they're using our software and libraries. It helps us to focus our efforts on our open source offerings, so we can create even more useful stuff for the community.

So don't hesitate to drop us a line at [TODO@bsb-muenchen.de](mailto:TODO@bsb-muenchen.de) if you could make use of the plugin :-)

## License

[Apache License 2.0](https://github.com/dbmdz/solr-ocr-plugin/blob/master/LICENSE)
