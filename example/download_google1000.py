#!/usr/bin/env python3
import sys
from concurrent.futures import ProcessPoolExecutor, as_completed
from io import BytesIO
from multiprocessing import cpu_count
from pathlib import Path
from zipfile import ZipFile

import lxml.etree as etree
import requests

URL_TEMPLATE = "http://commondatastorage.googleapis.com/books/icdar2007/Volume_{:04}.zip"

parser = etree.HTMLParser()


def download_volume(vol_num: int, out_dir: Path):
    vol_dir = out_dir / f'Volume_{vol_num:04}'
    if not vol_dir.exists():
        resp = requests.get(URL_TEMPLATE.format(vol_num))
        zf = ZipFile(BytesIO(resp.content))
        zf.extractall(str(out_dir))
    fix_hocr(vol_dir / 'hOCR.html')
    return vol_dir


def fix_hocr(hocr_path: Path):
    tree = etree.parse(str(hocr_path), parser=parser)
    title = tree.find('.//title')
    if title is not None:
        title.getparent().remove(title)
    for idx, page_elem in enumerate(tree.findall('.//div[@class="ocr_page"]'),
                                    start=1):
        page_elem.attrib['id'] = f'page_{idx}'
        for word_elem in page_elem.findall('.//span[@class="ocr_cinfo"]'):
            word_elem.attrib['class'] = 'ocrx_word'
    with hocr_path.open('wb') as fp:
        fp.write(etree.tostring(tree))


def main():
    out_dir = Path('./google1000')
    out_dir.mkdir(exist_ok=True)
    with ProcessPoolExecutor(max_workers=cpu_count()) as pool:
        futs = []
        for vol_num in range(1000):
            futs.append(pool.submit(download_volume, vol_num, out_dir))
        for idx, fut in enumerate(as_completed(futs)):
            fut.result()
            sys.stdout.write(f'{idx + 1}/1000\r')
            sys.stdout.flush()


if __name__ == '__main__':
    main()
