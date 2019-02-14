mod hocr;
mod miniocr;
mod alto;

fn main() {
    /* TODO:
     * - Read file path from arguments
     * - Read file into buffer
     * - Determine OCR format from buffer
     * - Call out to format implementations to get the converted format
     */
    println!("Empty string contains hOCR: {}", hocr::contains_hocr(""));
    println!("Empty string contains ALTO: {}", alto::contains_alto(""));
    println!("Empty string contains MiniOCR: {}", miniocr::contains_miniocr(""));
}
