#!/usr/bin/env python3
import re
import sys
import tarfile
from pathlib import Path

import requests


GOOGLE1000_PATH = Path('./google1000')
SOLR_HOST = 'localhost:8181'
SOLR_CORE = 'ocrtest'


class SolrException(Exception):
    def __init__(self, resp, payload):
        self.message = resp
        self.payload = payload


def index_documents(docs):
    resp = requests.post(
        "http://{}/solr/{}/update".format(SOLR_HOST, SOLR_CORE),
        json=docs, params=dict(softCommit="true"))
    if not resp:
        raise SolrException(resp.json(), docs)


def load_ocrtext(base_dir):
    base_dir = Path(base_dir)
    for vol_dir in base_dir.glob('Volume_*'):
        hocr_path = vol_dir / 'hOCR.html'
        if not hocr_path.exists():
            continue
        with hocr_path.open("rt") as fp:
            yield vol_dir.name, fp.read()


if __name__ == '__main__':
    ocr_iter = load_ocrtext(GOOGLE1000_PATH)
    batch = []
    for idx, (ident, ocr) in enumerate(ocr_iter):
        doc = dict(id=ident, ocr_text=ocr)
        batch.append(doc)
        if len(batch) == 5:
            index_documents(batch)
            batch = []
        sys.stdout.write(f'\r{idx}/1000')
        sys.stdout.flush()
