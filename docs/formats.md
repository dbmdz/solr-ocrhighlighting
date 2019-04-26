## hOCR

## ALTO

## MiniOCR

This plugin includes support for a custom OCR format that we dubbed *MiniOCR*. This format is intended for use cases
where using the existing OCR files is not possible (e.g. because they're in an unsupported format or because you don't
want to ASCII-encode them and still use the modern highlighting approach). In these cases, minimizing the storage
requirements for the derived OCR files is important, which is why we defined this minimalistic format.

A basic example looks like this:

```xml
<p id="page_identifier">
  <b>
    <l><w x="50 50 100 100">A</w> <w x="150 50 100 100">Line</w></l>
  </b>
</p>
```

The format uses the following elements for describing the page structure:

- `<p>` for pages, can have a `id` attribute that contains the page identifier
- `<b>` for "blocks", takes no attributes
- `<l>` for lines, takes no attributes
- `<w>` for words, takes a `box` attribute that contains the x and y offset and the width and height of the word in
  pixels on the page image. The coordinates can be either integrals or floating point values between 0 and 1 (to denote
  relative coordinates).