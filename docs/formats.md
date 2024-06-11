In general the plugin assumes that all OCR formats encode their documents
in a hierarchy of **blocks**. For all supported formats, we map their
block types to these general types:

- **Page**: optional if there is only a single page in a document
- **Block**: optional if [`hl.ocr.limitBlock`](query.md#available-highlighting-parameters) is set to a different value at
  query time
- **Section**: optional
- **Paragraph**: optional
- **Line**: (optional if [`hl.ocr.contextBlock`](query.md#available-highlighting-parameters) is set to a different value
  at query time)
- **Word**: *required*

These block types can be used in the [`hl.ocr.limitBlock` and `hl.ocr.contextBlock`
query parameters](query.md#available-highlighting-parameters) to control how the plugin generates snippets.

## hOCR

**Block type mapping:**

| Block     | hOCR class                  | notes                            |
| --------- | --------------------------- | -------------------------------- |
| Word      | `ocrx_word`                 | needs to have a `bbox` attribute with the coordinates on the page |
| Page      | `ocr_page`                  | needs to have a page identifier, either in `id` attribute or in the `ppageno` or `x_source` entry in the `title` attribute |
| Block     | `ocr_carea`/`ocrx_block`    |                                  |
| Section   | `ocr_chapter`/`ocr_section`/<br>`ocr_subsection`/`ocr_subsubsection` | |
| Paragraph | `ocr_par`                   |                                  |
| Line      | `ocr_line` or `ocrx_line`   |                                  |

## ALTO

!!! caution
    The coordinates returned by the plugin are **not always pixel values**, since ALTO supports a variety
    of different reference units for the coordinates. Check the `<MeasurementUnit>` value in your ALTO
    files, if its value is anything other than `pixel`, you will have to do some additional calculations
    on the client side to convert to pixel coordinates.

**Block type mapping:**

| Block     | ALTO tag                    | notes                            |
| --------- | --------------------------- | -------------------------------- |
| Word      | `<String />`                | needs to have `CONTENT`, `HPOS`, `VPOS`, `WIDTH` and `HEIGHT` attributes |
| Line      | `<TextLine />`              |                                  |
| Block     | `<TextBlock />`             |                                  |
| Page      | `<Page />`                  | needs to have an `ID` attribute with a page identifier |
| Section   | *not mapped*                |                                  |
| Paragraph | *not mapped*                |                                  |


## MiniOCR

This plugin also includes support for a custom non-standard OCR format that we dubbed *MiniOCR*, designed
to be very simple (and thus performant) to parse and to occupy the least space possible.

You should use this format when:

- you want to store the OCR in the index (to keep the index size as low)
- reusing the existing OCR files is not possible or practical (to keep occupied disk space low)
- you want the best possible performance, highlighting MiniOCR is ~25% faster than ALTO and ~50% faster than hOCR (in an artificial benchmark that is purely CPU-bound)

A basic example looks like this:

```xml
<ocr>
  <p xml:id="page_identifier">
    <b>
      <l><w x="50 50 100 100">A</w> <w x="150 50 100 100">Line</w></l>
    </b>
  </p>
</ocr>
```

Alternatives for words can be encoded with the `⇿` (`U+21FF`) marker. For example, this is how you would
encode a word with the default form `clistrias` and two alternatives `christmas` and `christrias`:

```xml
<w x="50 50 100 100">clistrias⇿christmas⇿christrias</w>
```

**Block type mapping:**

| Block     | MiniOCR tag  | notes                            |
| --------- | ------------ | -------------------------------- |
| Word      | `<w/>`       | needs to have `box` attribute with `{x} {y} {width} {height}`. <br>Values can be integers or floats between 0 and 1, **with the leading `0` omitted** |
| Line      | `<l/>`       |                                  |
| Block     | `<b/>`       |                                  |
| Page      | `<p/>`       | needs to have an `xml:id` attribute with a page identifier. Optionally can have a `wh` attribute with the `{width} {height}` values for the page |
| Section   | *not mapped* |                                  |
| Paragraph | *not mapped* |                                  |
