# solr-ocr-plugin

This Solr plugin lets you put OCR text into one or more of you documents' fields and then allows you to obtain structured highlighting data with the text and its position on the page at query time. All this without having to store the OCR data in the index itself, but at arbitrary external locations instead.

It works by extending Solr's standard `UnifiedHighlighter` with support for loading external field values and determining OCR positions from those field values. This means that (almost) all options and query types supported by the `UnifiedHighlighter` are also supported for OCR highlighting. The plugin also works transparently with non-OCR fields and just lets the default implementation handle those.

The plugin implements a number of usage scenarios, based on where the OCR documents are stored:

| **Location of OCR content** | **Index Size**                    | **Memory Usage**                                 | **Easy to index**                                                       | **Supports `hl.weightMatches`**     | **Uses existing UTF-8 files on disk** |
|-----------------------------|-----------------------------------|--------------------------------------------------|---------------------------------------------------------------------|-----------------------------------------|-----------------------------------------|
| Solr (stored field)         | ❌ Large, raw docs in index        | ❌ High, need to keep complete document in memory |  ✅ raw hOCR/ALTO/MiniOCR                                            | ✅                                       | ❌                                       |
| External (UTF-16 file)      | ✅ just offsets                    | ✅ low, only highlighted content is read          |  ✅ raw hOCR/ALTO/MiniOCR                                            | ✅                                       | ❌                                       |
| External (UTF-8 file)       | ✅ no stored fields, just payloads | ✅ low, only highlighted content is read          | ❓ Complicated for non-Java, need to determine byte offsets of words | ❌ Only "classic" highlighting supported | ✅                                       |

[1] Java implementations for all supported OCR formats is provided, native library/CLI tool planned

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
