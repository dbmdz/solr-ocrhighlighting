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
query parameters](query.md#params) to control how snippets are generated.

## hOCR

**Block type mapping:**

| Block     | hOCR class                  | notes                            |
| --------- | --------------------------- | -------------------------------- |
| Word      | `ocrx_word`                 | needs to have a `bbox` attribute with the coordinates on the page |
| Page      | `ocr_page`                  | needs to have a `ppageno` attribute with a page identifier |
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

This plugin also includes support for a custom non-standard OCR format that we dubbed *MiniOCR*.
This format is intended for use cases where reusing the existing OCR files is not possible or
practical. In these cases, minimizing the storage requirements for the derived OCR files is important,
which is why we defined this minimalistic format.

A basic example looks like this:

```xml
<ocr>
  <p id="page_identifier">
    <b>
      <l><w x="50 50 100 100">A</w> <w x="150 50 100 100">Line</w></l>
    </b>
  </p>
</ocr>
```

**Block type mapping:**

| Block     | MiniOCR tag  | notes                            |
| --------- | ------------ | -------------------------------- |
| Word      | `<w/>`       | needs to have `box` attribute with `{x} {y} {width} {height}`. <br>Values can be integers or floats between 0 and 1 |
| Line      | `<l/>`       |                                  |
| Block     | `<b/>`       |                                  |
| Page      | `<p/>`       | needs to have an `id` attribute with a page identifier |
| Section   | *not mapped* |                                  |
| Paragraph | *not mapped* |                                  |