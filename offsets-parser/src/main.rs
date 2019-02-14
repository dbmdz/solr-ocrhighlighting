extern crate clap;
extern crate html_entities;
#[macro_use] extern crate lazy_static;
extern crate regex;

use std::fs;
use std::io::{stdin,stdout,Write,BufRead,BufReader};
use std::path::Path;

use clap::App;
use html_entities::decode_html_entities;
use regex::Regex;

enum OcrFormat {
    Hocr,
    Alto,
    MiniOcr
}

fn get_format(ocr: &str) -> OcrFormat {
    lazy_static! {
        static ref MINIOCR_FORMAT_RE: Regex = Regex::new(r"^(?:<\?xml.+?\?>)?<ocr>").unwrap();
        static ref HOCR_FORMAT_RE: Regex = Regex::new(r#"<div class=['"]ocr_page['"].*?>"#).unwrap();
        static ref ALTO_FORMAT_RE: Regex = Regex::new(r"<alto[ >]").unwrap();
    }
    if MINIOCR_FORMAT_RE.is_match(ocr) {
        OcrFormat::MiniOcr
    } else if HOCR_FORMAT_RE.is_match(ocr) {
        OcrFormat::Hocr
    } else if ALTO_FORMAT_RE.is_match(ocr) {
        OcrFormat::Alto
    } else {
        panic!("Unknown format!")
    }
}

fn get_regex(ocr: &str) -> &Regex {
    lazy_static! {
        static ref MINIOCR_WORD_RE: Regex = Regex::new(r#"<w.*?>(?P<text>[^\s]+?)</w>"#).unwrap();
        static ref HOCR_WORD_RE: Regex = Regex::new(r#"<span class=['"]ocrx_word['"].+?>(?P<text>[^\s]+?)</span>"#).unwrap();
        static ref ALTO_WORD_RE: Regex = Regex::new(r#"<String.+?CONTENT="(?P<text>.+?)".*?>"#).unwrap();
    }
    match get_format(&ocr) {
        OcrFormat::MiniOcr => &MINIOCR_WORD_RE,
        OcrFormat::Hocr => &HOCR_WORD_RE,
        OcrFormat::Alto => &ALTO_WORD_RE,
    }
}

fn main() {
    let matches = App::new("Byte Offset converter for hOCR/ALTO/MiniOCR")
        .version("0.1.0")
        .author("Johannes Baiter <johannes.baiter@bsb-muenchen.de>")
        .about("Converts OCR documents into a whitespace-separated sequence of <word><delimiter><byte_offset> tokens.")
        .args_from_usage(
            "-d, --delimiter     'the delimiter to be used, defaults to ⚑'
            -o, --output         'path to write the converted output to, defaults to stdout'
            [OCR_DOCUMENT]       'the OCR document to be converted, defaults to stdin'")
        .get_matches();

    let delimiter = matches.value_of("delimiter").unwrap_or("⚑");
    let input = matches.value_of("OCR_DOCUMENT");

    let mut reader: Box<BufRead> = match input {
        Some(fname) => Box::new(BufReader::new(fs::File::open(fname).unwrap())),
        None => Box::new(BufReader::new(stdin())),
    };
    let mut ocr_text = String::new();
    reader.read_to_string(&mut ocr_text).unwrap();

    let mut out_writer = match matches.value_of("output") {
        Some(p) => Box::new(fs::File::create(&Path::new(p)).unwrap()) as Box<Write>,
        None    => Box::new(stdout()) as Box<Write>,
    };
    let word_re = get_regex(&ocr_text);
    for cap in word_re.captures_iter(&ocr_text) {
        let text = decode_html_entities(&cap["text"]).unwrap();
        let start_offset = cap.get(0).unwrap().start();
        write!(out_writer, "{}{}{} ", text, delimiter, start_offset).unwrap();
    }
}
