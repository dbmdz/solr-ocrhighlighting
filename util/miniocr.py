#!/usr/bin/env python3
""" Simple script to convert hOCR or ALTO to miniOCR format.

Does not support ALTO documents without explicitely encoded whitespace (via `SP` tags).

Usage:
    miniocr.py [input] [-o output]

    input: Path to hOCR or ALTO file, if not provided, read from stdin
    output: Path to output file, if not provided, write to stdout

Examples:
    miniocr.py input.hocr > output.miniocr
    miniocr.py input.alto -o output.miniocr
    cat input.hocr | miniocr.py > output.miniocr
    miniocr.py < input.hocr > output.miniocr
    miniocr.py < input.alto -o output.miniocr
"""
from __future__ import annotations

import argparse
import enum
import html
import io
import re
import select
import sys
from dataclasses import dataclass
from html.entities import html5 as html_entities
from pathlib import Path
from typing import Iterable
from xml.etree import ElementTree as ET


HOCR_PROP_PATS = [
    re.compile("(?P<key>bbox) (?P<value>\\d+ \\d+ \\d+ \\d+)"),
    re.compile("(?P<key>ppageno) (?P<value>\\d+)"),
    re.compile('(?P<key>x_source) "?(?P<value>[^;]+)"?'),
    re.compile('(?P<key>image) "(?P<value>\\d+)"'),
]
ENTITIES_PAT = re.compile(rf"&({'|'.join(k for k in html_entities.keys())});")


class EventKind(enum.Enum):
    START = 1
    END = 2
    TEXT = 3


class BoxType(enum.Enum):
    PAGE = 1
    BLOCK = 2
    LINE = 3
    WORD = 4

    @classmethod
    def from_hocr_class(cls, val: str | None) -> BoxType | None:
        match val:
            case "ocr_page":
                return BoxType.PAGE
            case "ocr_carea" | "ocr_par" | "ocrx_block":
                return BoxType.BLOCK
            case "ocr_line":
                return BoxType.LINE
            case "ocrx_word":
                return BoxType.WORD

    @classmethod
    def from_alto_tag(cls, val: str) -> BoxType | None:
        match val:
            case "Page":
                return BoxType.PAGE
            case "PrintSpace":
                return BoxType.BLOCK
            case "TextBlock":
                return BoxType.BLOCK
            case "TextLine":
                return BoxType.LINE
            case "String":
                return BoxType.WORD

    @classmethod
    def to_miniocr_tag(cls, val: BoxType) -> str:
        match val:
            case BoxType.PAGE:
                return "p"
            case BoxType.BLOCK:
                return "b"
            case BoxType.LINE:
                return "l"
            case BoxType.WORD:
                return "w"


@dataclass
class ParseEvent:
    kind: EventKind
    box_type: BoxType | None
    page_id: str | None = None
    x: int | float | None = None
    y: int | float | None = None
    width: int | float | None = None
    height: int | float | None = None
    text: str | None = None


def convert_entity(entity: str) -> str:
    """ Since Python's stdlib parser can't handle named entities, we have to convert them to numeric entities. """
    # Special case: ASCII characters that need to be escaped for well-formed markup
    if entity == "lt":
        return "&#60;"
    elif entity == "gt":
        return "&#62;"
    elif entity == "amp":
        return "&#38;"
    elif entity == "quot":
        return "&#34;"
    elif entity == "apos":
        return "&#39;"
    else:
        return html_entities[entity].encode("ascii", "xmlcharrefreplace").decode("utf8")


def parse_hocr(hocr: bytes) -> Iterable[ParseEvent]:
    # hOCR can used named entities, which Python's stdlib parser can't handle, so we
    # have to convert them to numeric entities
    fixed_hocr = ENTITIES_PAT.sub(
        lambda match: convert_entity(match.group(1)),
        hocr.decode("utf-8"),
    )

    # Track the currently parsed word across parse iterations to be able to
    # add alternatives to the word text
    cur_word: ParseEvent | None = None
    # Track if we're currently parsing alternatives for a word
    in_word_alternatives = False

    for evt in ET.iterparse(io.StringIO(fixed_hocr), events=("start", "end")):
        event, elem = evt
        kind = EventKind.START if event == "start" else EventKind.END
        box_type = BoxType.from_hocr_class(elem.attrib.get("class"))
        # Strip namespace from tag
        tag = elem.tag.split("}")[-1]

        if (
            cur_word is not None
            and tag == "span"
            and elem.attrib.get("class") == "alternatives"
        ):
            in_word_alternatives = True

        if kind == EventKind.START and cur_word is not None and in_word_alternatives:
            alternatives: list[str] = []
            if cur_word.text:
                alternatives.append(cur_word.text)
            if tag == "ins" and elem.text:
                alternatives.insert(0, elem.text)
            elif tag == "del" and elem.text:
                alternatives.append(elem.text)
            cur_word.text = "⇿".join(alternatives)

        if box_type is None:
            continue

        evt = ParseEvent(kind=kind, box_type=box_type)

        if evt.kind == EventKind.END:
            if evt.box_type == BoxType.WORD and cur_word is not None:
                # Emit the word event if the word has ended and we have picked up all
                # potential alternative readings within it
                yield cur_word
                cur_word = None
                in_word_alternatives = False
            yield evt
            if evt.box_type == BoxType.WORD and elem.tail:
                # hOCR has support for coordinate-less text nodes between words, emit these
                # as text events
                yield ParseEvent(kind=EventKind.TEXT, box_type=None, text=elem.tail)
            elem.clear()
            continue

        props = {
            match.group("key"): match.group("value")
            for match in (
                pat.search(elem.attrib.get("title")) for pat in HOCR_PROP_PATS
            )
            if match is not None
        }
        evt.page_id = next(
            (props[x] for x in ("x_source", "ppageno", "image") if x in props), None
        )
        if "bbox" in props:
            ulx, uly, lrx, lry = map(int, props["bbox"].split())
            evt.x, evt.y = ulx, uly
            evt.width, evt.height = lrx - ulx, lry - uly

        if evt.box_type == BoxType.WORD:
            # Don't emit word events immediately, since we might have alternatives
            evt.text = elem.text
            cur_word = evt
            continue

        yield evt


def parse_alto(alto: bytes) -> Iterable[ParseEvent]:
    # ALTO documents can have coordinates expressed as 1/1200th of an inch or as millimeters,
    # in these cases we can only use relative coordinates in the MiniOCR output format, since
    # we don't know the DPI of the original document.
    use_relative = False
    relative_reference: tuple[int, int] | None = None

    # We track the currently parsed word across parse iterations to be able to
    # add alternatives to the word text
    cur_word: ParseEvent | None = None
    for evt in ET.iterparse(io.BytesIO(alto), events=("start", "end")):
        event, elem = evt
        kind = EventKind.START if event == "start" else EventKind.END
        # Strip namespace from tag
        tag = elem.tag.split("}")[-1]

        if kind == EventKind.START:
            if tag == "SP":
                yield ParseEvent(kind=EventKind.TEXT, box_type=None, text=" ")

            if tag == "MeasurementUnit" and elem.text != "pixel":
                use_relative = True

            if tag == "ALTERNATIVE" and cur_word is not None:
                cur_word.text = f"{cur_word.text}⇿{elem.text}"

        box_type = BoxType.from_alto_tag(tag)
        if box_type is None:
            continue

        evt = ParseEvent(kind=kind, box_type=box_type)

        if evt.kind == EventKind.END:
            # We only emit the word event if the word has ended, since there might
            # have been alternatives within the word element in the ALTO
            if evt.box_type == BoxType.WORD and cur_word is not None:
                yield cur_word
                cur_word = None
            yield evt
            elem.clear()
            continue

        if tag == "Page":
            if "ID" in elem.attrib:
                evt.page_id = elem.attrib["ID"]
            if use_relative:
                relative_reference = (
                    int(elem.attrib["WIDTH"]),
                    int(elem.attrib["HEIGHT"]),
                )

        if all(attr in elem.attrib for attr in ["HPOS", "VPOS", "WIDTH", "HEIGHT"]):
            evt.x = float(elem.attrib["HPOS"])
            evt.y = float(elem.attrib["VPOS"])
            evt.width = float(elem.attrib["WIDTH"])
            evt.height = float(elem.attrib["HEIGHT"])
            if use_relative and relative_reference:
                # Non-pixel coordinates are emitted as relative coordinates
                evt.x = evt.x / relative_reference[0]
                evt.y = evt.y / relative_reference[1]
                evt.width = evt.width / relative_reference[0]
                evt.height = evt.height / relative_reference[1]
            else:
                evt.x = int(evt.x)
                evt.y = int(evt.y)
                evt.width = int(evt.width)
                evt.height = int(evt.height)

        if evt.box_type == BoxType.WORD:
            evt.text = elem.attrib.get("CONTENT")

            # Handle hyphenation
            subs_type = elem.attrib.get("SUBS_TYPE")

            if subs_type == "HypPart1":
                evt.text = f"{evt.text}\xad"

            cur_word = evt
            continue
        yield evt


def generate_miniocr(evts: Iterable[ParseEvent]) -> Iterable[str]:
    yield "<ocr>"

    # Used to determine if there should be inter-line whitespace
    last_txt_was_hyphen = False

    for evt in evts:
        if evt.kind == EventKind.TEXT and evt.text:
            # Ignore whitespace-only text if the last text ended on a hyphen
            if last_txt_was_hyphen and not evt.text.strip():
                continue
            yield evt.text
            last_txt_was_hyphen = evt.text.endswith("\xad")
            continue

        if evt.box_type is None:
            continue

        if evt.kind == EventKind.START:
            tag = BoxType.to_miniocr_tag(evt.box_type)
            attribs = []
            if evt.box_type == BoxType.PAGE:
                if evt.page_id:
                    attribs.append(f'xml:id="{evt.page_id}"')
                if (
                    evt.width
                    and evt.height
                    and all(isinstance(x, int) for x in (evt.width, evt.height))
                ):
                    # Only add page dimensions if we have integers, i.e. pixel dimensions
                    attribs.append(f'wh="{evt.width} {evt.height}"')
            elif evt.box_type == BoxType.WORD:
                if all(x is not None for x in (evt.x, evt.y, evt.width, evt.height)):
                    if all(
                        isinstance(x, float)
                        for x in (evt.x, evt.y, evt.width, evt.height)
                    ):
                        # Relative coordinates are always floats, encoded without the leading zero
                        # and with four decimal places
                        attribs.append(
                            "x="
                            + " ".join(
                                f"{x:.4f}"[1:]
                                for x in (evt.x, evt.y, evt.width, evt.height)
                            )
                        )
                    else:
                        attribs.append(f'x="{evt.x} {evt.y} {evt.width} {evt.height}"')
            yield f'<{tag} {" ".join(attribs)}>' if len(attribs) > 0 else f"<{tag}>"
            if evt.box_type == BoxType.WORD:
                if evt.text is not None:
                    last_txt_was_hyphen = evt.text.endswith("\xad")
                    yield html.escape(evt.text)
        elif evt.kind == EventKind.END:
            yield f"</{BoxType.to_miniocr_tag(evt.box_type)}>"
            if evt.box_type == BoxType.LINE and not last_txt_was_hyphen:
                # Add inter-line whitespace if the line did not end on a hyphenation
                yield " "
    yield "</ocr>"


def main(input: bytes, output: Path | None):
    header_block = input[:512]
    if b'<alto' in header_block:
        parse_iter = parse_alto(input)
    else:
        parse_iter = parse_hocr(input)
    if output is None:
        fp = sys.stdout
    else:
        fp = output.open("w")
    try:
        for chunk in generate_miniocr(parse_iter):
            fp.write(chunk)
    finally:
        if output is not None:
            fp.close()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Convert hOCR or ALTO to miniOCR")
    parser.add_argument(
        "input",
        type=Path,
        default=None,
        help="Input file (hOCR or ALTO), if not provided, read from stdin",
        nargs='?',
    )
    parser.add_argument(
        "-o, --output",
        dest="output",
        type=Path,
        help="Output file, if not provided, write to stdout",
        default=None
    )
    args = parser.parse_args()

    if args.input is None:
        # Help users who accidentally launched it without arguments from a
        # terminal by telling them that we're waiting for input on stdin
        if sys.stdin.isatty():
            while True:
                rlist, _, _ = select.select([sys.stdin], [], [], 3)
                if rlist:
                    break
                print("Waiting for input on stdin...", file=sys.stderr, end="\r")
        main(sys.stdin.buffer.read(), args.output)
    else:
        main(args.input.read_bytes(), args.output)