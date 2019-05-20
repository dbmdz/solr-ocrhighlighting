# offsets-parser

A small cross-platform command-line utility to generate a sequencde of tokens along with their byte offset
in the input document.

# Usage
```
Byte Offset converter for hOCR/ALTO/MiniOCR 0.1.0
Johannes Baiter <johannes.baiter@bsb-muenchen.de>
Converts OCR documents into a whitespace-separated sequence of <word><delimiter><byte_offset> tokens.

USAGE:
    offsets-parser [FLAGS] [OCR_DOCUMENT]

FLAGS:
    -d, --delimiter    the delimiter to be used, defaults to ⚑
    -h, --help         Prints help information
    -o, --output       path to write the converted output to, defaults to stdout
    -V, --version      Prints version information

ARGS:
    <OCR_DOCUMENT>    the OCR document to be converted, defaults to stdin
```

## Building

The tool is written in [Rust](https://www.rust-lang.org/), so you need to install the latest stable version
to be able to build the tool ([rustup](https://www.rust-lang.org/tools/install) is recommended).

Once you've installed Rust, this is all you need to do:

```
$ cargo build --release
```

The resulting binary will be in `target/release`.