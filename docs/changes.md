## 0.4.0 (tbd.)

This is a major release with a focus on compatibility and performance.

- **Fixes compatibility with Solr/Lucene 8.4 and 7.6**. We now also have an integration test suite that checks for
 compatibility with all Solr versions >= 7.5 on every change, so compatibility breakage is kept to a minimum in the
  future.

**New parameters:**
- **Add new `pages` key to snippet response with page dimensions**. This can be helpful if you need to calculate
  the snippet coordinates relative to the page image dimensions.

**Format changes:**
- hocr: Add support for retrieving page identifier from `x_source` an `ppageno` properties
- hocr: Strip out title tag during indexing and highlighting
- ALTO: The plugin now supports ALTO files with coordinates expressed as floating point numbers (thanks to @mspalti!)

**Performance:**
- Add concurrent preloading for highlighting target files. This can result in a nice performance boost, since by the
  time the plugin gets to actually highlighting the files, their contents are already in the OS' page cache. See
  the [Performance Tuning section in the docs](https://dbmdz.github.io/solr-ocrhighlighting/performance/) for more
   context.
- This release changes the way we handle UTF-8 during context generation, resulting in an additional ~25% speed up
  compared to previous versions.

**Miscellaneous:**
- Log warnings during source pointer parsing
- Filter out empty files during indexing
- Add new documentation section on performance tuning

## 0.3.1 (2019-07-26)

This is patch release that fixes compatibility with Solr/Lucene 8.2.


## 0.3 (2019-07-25)
[GitHub Release](https://github.com/dbmdz/solr-ocrhighlighting/releases/tag/0.3)

This release brings some sweeping changes across the codebase, all aimed at making
the plugin much simpler to use and less complicated to maintain. However, this also
means **a lot of breaking changes**. It's best to go through
[the documentation](https://dbmdz.github.com/solr-ocrhighlighting) (which has been
simplified and was largely rewritten) again and see what changes you need to apply
to your setup.

- **Specifying path resolving is no longer neccessary.** You now pass a pointer to one
  or more files (or regions thereof) directly in the index document. The pointer
  will be stored with the document and used to locate the input file(s) during
  highlighting. Refer to the [documentation](https://dbmdz.github.com/solr-ocrhighlighting/indexing.md)
  for more details. This should also **increase indexing performance** and **decrease
  the memory requirements**, since at no point does the complete OCR document need
  to be held in memory.
- **`hl.weightMatches` now works with UTF8**. You no longer need to ASCII-encode
  your OCR files to be able to use Solr's superior highlighting approach. Due to the
  first change, the plugin now takes care of mapping UTF8 byte-offsets to character
  offsets by itself. This also means all code related to storing byte offsets in
  payloads is gone.
- **Specifying the OCR format is no longer neccessary.** The plugin now offers a
  single `OcrFormatCharFilter` that will auto-detect the OCR format used for a given
  document and select the correct analysis chain. This means that **using multiple
  OCR formats for the same field is now possible!**
- **Performance improvements.** Some optimizations were done to the way the plugin
  seeks through the OCR files. You should see a substantial performance improvement
  for documents with a low density of multi-byte codepoints, especially English.
  Also included is a new `hl.ocr.maxPassages` parameter to control how many passages
  are looked at for building the response, which can have an enormous impact on
  performance.

**Major Breaking Changes**:

- `HighlightComponent` is now called `OcrHighlightComponent` for more clarity
- OCR fields to be highlighted now need to be passed with the `hl.ocr.fl` parameter
- Auto-detection of highlightable fields is no longer possible with the standard
  highlighter, fields to be highlighted need to be passed explicitely with the
  `hl.fl` parameter
- In the order of components, the OCR highlighting component needs to come *before*
  the standard highlighter to avoid conflicts.

## 0.2 (2019-07-16)
[GitHub Release](https://github.com/dbmdz/solr-ocrhighlighting/releases/tag/0.2)

* **Breaking Change**: ALTO and hOCR now have custom `CharFilter` implementations
  that should be used instead of `HTMLStripCharFilterFactory`.
  Refer to [the documentation](https://dbmdz.github.io/solr-ocrhighlighting/formats/)
  for more details.
* **Feature:** Resolve Hyphenation at indexing time for all supported formats. If a
  word is broken across multiple lines, it will be indexed as the dehyphenated form.
  During highlighting, the parts on both lines will be highlighted appropriately.
* **Fix** calculation of passages with matches spanning multiple lines, in previous
  versions some passages would be too small
* **Fix** `hl.fl` parameter handling, a bug in 0.1 made this parameter not have any effect


## 0.1 (2019-06-06)
[GitHub Release](https://github.com/dbmdz/solr-ocrhighlighting/releases/tag/0.1)

- Initial Release
