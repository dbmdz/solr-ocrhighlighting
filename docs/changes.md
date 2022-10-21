## 0.8.3 (2022-10-21)
[GitHub Release](https://github.com/dbmdz/solr-ocrhighlighting/releases/tag/0.8.3)

Another bugfix release, fixing some edge cases with 'odd' OCR files.

**Bugfixes:**

- hOCR: Fix truncated passages during highlighting due to incomplete forward
  passes while parsing candidate passages.
- All Formats: Use an iterative solution for skipping empty words instead of
  a recursive strategy, which could lead to stack overflows when encountering
  OCR files with many empty words.

**Other Changes:**
- We now have pre-releases in the Solr repository that can be used to experiment
  with the latest changes in the plugin before the official release. For users not
  using the repository, a pre-release build is also pushed to the GitHub Releases
  page on every update to the repository.

## 0.8.2 (2022-09-22)
[GitHub Release](https://github.com/dbmdz/solr-ocrhighlighting/releases/tag/0.8.2)

Bugfix release for an edge-case in hOCR parsing.

**Bugfixes:**

- hOCR: Fix stack overflow when handling empty words in combination with a partially
  hyphenated word

**Other Changes:**

- Improved error message in case of errors during highlighting, the message now
  includes the source pointer of the failed document, or if storing OCR in the index,
  the beginning of the broken content. Also included is the internal Lucene document
  identifier.  [By adding the `[docid]` field to the returned fields for the failing
  query](https://solr.apache.org/guide/solr/latest/query-guide/document-transformers.html#docid-docidaugmenterfactory),
  the internal id is added to very document in the result set for a failing query,
  which should allow quick identification of the documents that cause issues during
  highlighting.

## 0.8.1 (2022-06-10)
[GitHub Release](https://github.com/dbmdz/solr-ocrhighlighting/releases/tag/0.8.1)

This is a bugfix release targeting mainly the MiniOCR and ALTO
implementations.

**Bugfixes:**

- ALTO: Fix handling of empty words. Previously any words after a word element
  with no text **would be skipped entirely during indexing** 😱😱.
- MiniOCR: Fix handling of empty words, Previously a word element with no text
  would make the parser crash.
- MiniOCR: Make the `wh` attribute on `<p>` page elements actually optional.
  The documentation said it was optional, but the parser would crash when
  attempting to handle elements without the attribute

**Other Changes:**

- A warning will now be logged if none of the fields requested with `hl.ocr.fl`
  exist or are defined as stored fields. Previously highlighting would just
  not work, with no indications to users as to why this was the case.


## 0.8.0 (2022-06-01)
[GitHub Release](https://github.com/dbmdz/solr-ocrhighlighting/releases/tag/0.8.0)

The major improvement in this version is compatibility with Solr 9.

Due to a number of API changes in Solr and Lucene, we now have to ship two separate releases,
one for Solr 7 and 8 and one for Solr 9, so please take extra care when downloading to pick
the correct release. In the Package Repository, the Solr 7/8 release will always have version
with the suffix `-solr78`.

We also **changed the package namespaces** for all user-facing components so they are easier
to identify and write. What this means is that you will need to change the `class="..."`
attributes in your  `solrconfig.xml` and `schema.xml` to match the new package namespaces.
Whenever you previously had `de.digitalcollections.solrocr.<other stuff>.ClassName`, you
now have to simply write `solrocr.ClassName`.

**New Features:**

- For users running Solr in the Solrcloud mode, the plugin can now be installed via Solr's
  [Package Manager](https://solr.apache.org/guide/solr/latest/configuration-guide/package-manager.html):
  ```
  $ bin/solr package add-repo dbmdz.github.io https://dbmdz.github.io/solr
  $ bin/solr package install ocrhighlighting  # For Solr 9
  $ bin/solr package install ocrhighlighting:0.8.0-solr78  # For Solr 7 and 8
  ```
  Note that Solr 7/8 users need to manually specify the version.

**API changes:**

- Changed deployment process to use two separate packages, one for Solr 9 and later and one for Solr 7/8, with a `-solr78.jar` suffix
- Changed namespace of all user-facing components to simply `solrocr` and moved all
  user-facing component classes to it:
    * `de.digitalcollections.solrocr.lucene.filters.OcrCharFilterFactory`<br>
      → `solrocr.OcrCharFilterFactory`
    * `de.digitalcollections.solrocr.lucene.filters.ExternalUtf8ContentFilterFactory`<br>
      → `solrocr.ExternalUtf8ContentFilterFactory`
    * `de.digitalcollections.solrocr.lucene.OcrAlternativesFilterFactory`<br>
      → `solrocr.OcrAlternativesFilterFactory`
    * `de.digitalcollections.solrocr.lucene.OcrHighlightComponent`<br>
      → `solrocr.OcrHighlightComponent`

**Bugfixes**

- Fix handling of quoted property values in hOCR title tags. We deviate a bit from the spec
  to be more compatible with existing real-world data: Values like `x_source` can now either
  be quoted in single- or double-quotes, or not at all, the parser will handle every case.

## 0.7.2 (2022-03-22)
[GitHub Release](https://github.com/dbmdz/solr-ocrhighlighting/releases/tag/0.7.2)

And yet another bugfix release.

**Bugfixes:**

- Fixed using single-quotes in MiniOCR input, previously these files were not recognized as valid MiniOCR files
  ([#247](https://github.com/dbmdz/solr-ocrhighlighting/pull/247), thanks @mspalti for the fix!)
- Fixed `OutOfBoundsException` when using alternatives with very long tokens (
  [#230](https://github.com/dbmdz/solr-ocrhighlighting/pull/230), thanks @fd17 for the report and review)

## 0.7.1 (2021-09-24)
[GitHub Release](https://github.com/dbmdz/solr-ocrhighlighting/releases/tag/0.7.1)

Another bugfix release, upgrading is recommended.

**Bugfixes:**

- Fix text display and "number of snippets" slider in demo setup
- Fix instances where we were using Java SDK methods that relied on a default locale, which led to
  hard-to-debug issues in some locales
- Fix an issue where a highlight rectangle would sometimes be oversized
- Fix issue in XML input validation when encountering very long XML opening tags
- Really fix handling of documents with no content (at all)
- Fix issue with namespaced ALTO documents

## 0.7.0 (2021-07-12)
[GitHub Release](https://github.com/dbmdz/solr-ocrhighlighting/releases/tag/0.7.0)

This is a bugfix release, especially users with ALTO files are encouraged to upgrade. Other than
bugfixes, this is the first release to support Solr 8.9.

**Bugfixes:**

- Fix handling of documents with no content
- Fix issue with the handling of unicode-encoded files
- Fix issue when an hOCR file had empty OCR boxes

## 0.6.0 (2021-05-11)
[GitHub Release](https://github.com/dbmdz/solr-ocrhighlighting/releases/tag/0.6.0)

This is a major new release with significant improvements in stability, accuracy and most importantly performance.
Updating is **highly** recommended, especially for ALTO users, who can expect a speed-up in indexing of up to
**6000% (i.e. 60x as fast)**. We also recommend updating your JVM to at least Java 11 (LTS), since Java 9 introduced
[a feature](https://arnaudroger.github.io/blog/2017/06/14/CompactStrings.html) that speeds up highlighting
significantly.

**Performance:**

- **Indexing performance drastically improved for ALTO, slightly worse for hOCR and MiniOCR.** Under the
  hood we switched from Regular Expression and Automaton-based parsing to a proper XML parser to support
  more features and improve correctness.
  This drastically improved indexing performance for ALTO (6000% speedup, the previous
  implementation was pathologically slow), but caused a big hit for hOCR (~57% slower) and a slight hit
  for MiniOCR (~15% slower). These numbers are based on benchmarks done on a ramdisk, so the changes are very
  likely to be less pronounced in practice, depending on the choice of storage.
  **Note that this makes the parser also more strict in regard to whitespace.** If you were indexing OCR documents
  without any whitespace between word elements before, you will run into problems
  (see [#147](https://github.com/dbmdz/solr-ocrhighlighting/issues/147#issuecomment-800452975)).
- **Highlighting performance significantly improved for all formats.**
  The time for highlighting a single snippet has gone down for all formats
  (ALTO 12x as fast, hOCR 10x as fast, MiniOCR 6x as fast). Again, these numbers are based on benchmarks
  performed on a ramdisk and might be less pronounced in practice, depending on the storage layer.

**New Features:**

- **Indexing alternative forms encoded in the source OCR files.**
  All supported formats offer a way to encode alternative readings for recognized words. The plugin can now
  parse these from the input files and index them at the same position as the default form. This is a form
  of index-time term expansion (much like the [Synonym Graph Filter](https://lucene.apache.org/solr/guide/8_7/filter-descriptions.html#synonym-graph-filter)
  shipping with Solr). For example, if you OCR file has the alternatives `christmas` and `christrias` for the token
  `clistrias` in the span `presents on clistrias eve`, users would be able to search for `"presents christmas"` and
  `"presents clistrias"` and would get the correct match in both cases, both with full highlighting.
  Refer to the corresponding [section in the documentation](../alternatives) for instructions on setting it up.
- **On-the-fly repair of 'broken' markup.**
  `OcrCharFilterFactory` has a new option `fixMarkup` that enables on-the-fly repair of invalid XML in OCR input documents,
  namely problems that can arise when the markup contains unescaped instances of `<`, `>` and `&`.
  This option is disabled by default, we recommend enabling it when your OCR engine exhibits this problem and you
  are unable to fix the files on disk, since it incurs a bit of a performance hit during indexing.
- **Return snippets in order of appearance**.
  By default, Solr scores each highlighted passage as a "mini-document" and returns the passages
  ordered by their decreasing score. While this is a good match for a lot of use cases, there are
  many other that are better suited with a simple by-appearance order. This can now be controlled
  with the new `hl.ocr.scorePassages` parameter, which will switch to the by-appearance sort order
  if set to `off` (it is set to `on` by default)


**API changes:**

- **No more need for an explicit `hl.fl` parameter for highlighting non-OCR fields.** By default,
  if highlighting is enabled and  no `hl.fl` parameter is passed by the user, Solr falls back to
  highlighting every stored field  in the document. Previously this did not work with the plugin and
  users had to always explicitly specify which fields they wanted to have highlighted. *This is no
  longer neccessary*, the default behavior now works as expected.
- **Add a new `hl.ocr.trackPages` parameter to disable page tracking during highlighting.**
  This is intended for users who index one page per document, in these cases seeking backwards to determine
  the page identifier a match is not needed, since the containing document contains enough information to
  identify the page, improving highlighting performance due to the need for less backwards-seeking in the
  input files.
- **Add new `expandAlternatives` attribute to `OcrCharFilterFactory`.** This enables the parsing of
   alternative readings from input files (see above and the [corresponding section in the documentation](../alternatives))
  - **Add new `hl.ocr.scorePassages` parameter to disable sorting of passages by their score.**
    See the above section unter *New Features* for an explanation of this flag.

**Bugfixes:**

- **Improved tolerance for incomplete bounding boxes.** Previously the occurrence of an incomplete
  bounding box in a snippet (i.e. with one or more missing coordinates) would crash the whole query.
  We now simply insert a `0` default value in these cases.
- **Improvements in the handling of hyphenated terms.** This release fixes a
  few bugs in edge cases when handling hyphenated words during indexing,
  highlighting and snippet text generation.
- **Handle empty field values during indexing.** This would previously lead to an exception since
  the OCR parsers would try to either load a file from the empty string or parse OCR markup from it.

## 0.5.0 (2020-10-07)
[GitHub Release](https://github.com/dbmdz/solr-ocrhighlighting/releases/tag/0.5.0)

No breaking changes this time around, but a few essential bugfixes, more stability and a new feature.

**API changes:**

- **Snippets are now sorted by their descending score/relevancy.** Previously the order was non-deterministic, which
  broke the use case for dynamically fetching more snippets.
- **Add a new boolean `hl.ocr.alignSpans` parameter to align text and image spans.** This new option (disabled by
  default) ensures that the spans in text and image match, i.e. it forces the `<em>...</em>` in the highlighted text
  to correspond to actual OCR word boundaries.

**Bugfixes:**

- **Fix regular highlighting in distributed setup.** Regular, non-OCR highlighting was broken in previous versions due
  to a bad check in the shard response collection phase if users only requested regular highlighting, but not for OCR
  fields
- **Highlight spans are now always consistent with the spans designated in text.** Due to a bug, it would sometimes
  happen that the number of spans was inconsistent between the two.
- **Fix de-hyphenation in ALTO region texts.** Previously only the complete snippet text would be de-hyphenated, but not
  the individual regions.
- **Fix post-match content detection in ALTO.** A bug in this part of the code resulted in crashes when highlighting
  certain ALTO documents.


## 0.4.1 (2020-06-02)
[GitHub Release](https://github.com/dbmdz/solr-ocrhighlighting/releases/tag/0.4.1)

This is a patch release with a fix for excessive memory usage during indexing.

## 0.4.0 (2020-05-11)
[GitHub Release](https://github.com/dbmdz/solr-ocrhighlighting/releases/tag/0.4.0)

This is a major release with a focus on compatibility and performance.

- **Fixes compatibility with Solr/Lucene 8.4 and 7.6**. We now also have an integration test suite that checks for
 compatibility with all Solr versions >= 7.5 on every change, so compatibility breakage should be kept to a minimum in
 the future.

**Breaking API changes:**

- **Add new `pages` key to snippet response with page dimensions**. This can be helpful if you need to calculate
  the snippet coordinates relative to the page image dimensions.
- **Replace `page` key on regions and highlights with `pageIdx`**. That is, instead of a string with the
  corresponding page identifier, we have a numerical index into the `pages` array of the snippett. This reduces
  the redundancy introduced by the new `pages` parameter at the cost of having to do some pointer chasing in clients.
- **Add new `parentRegionIdx` key on highlights.** This is a numerical index into the `regions` array and allows for
  multi-column/multi-page highlighting, where a single highlighting span can be composed of regions on multiple
  disjunct parts of the page or even multiple pages.

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
- Empty regions or regions with only whitespace are no longer included in the output

## 0.3.1 (2019-07-26)
[GitHub Release](https://github.com/dbmdz/solr-ocrhighlighting/releases/tag/0.3.1)

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
