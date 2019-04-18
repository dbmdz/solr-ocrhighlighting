#!/usr/bin/env python3
import json
import re
import sys
import tarfile
from pathlib import Path
from urllib import request


GOOGLE1000_PATH = './google1000'
GOOGLE1000_URL = 'https://ocrhl.jbaiter.de/data/gbooks1000_hocr.tar.xz'
SOLR_HOST = 'localhost:8181'
SOLR_CORE = 'ocrtest'


class SolrException(Exception):
    def __init__(self, resp, payload):
        self.message = resp
        self.payload = payload


def are_volumes_missing(base_path):
    for vol_no in range(1000):
        vol_path = base_path / 'Volume_{:04}.hocr'.format(vol_no)
        if not vol_path.exists():
            return True
    return False


def load_ocrtext(base_path):
    if are_volumes_missing(base_path):
        print("Downloading missing volumes to {}".format(base_path))
        base_path.mkdir(exist_ok=True)
        with request.urlopen(GOOGLE1000_URL) as resp:
            tf = tarfile.open(fileobj=resp, mode='r|xz')
            for ti in tf:
                if not ti.name.endswith('.hocr'):
                    continue
                vol_id = ti.name.split('/')[-1].split('.')[0]
                ocr_text = tf.extractfile(ti).read()
                doc_path = base_path / '{}.hocr'.format(vol_id)
                if not doc_path.exists():
                    with doc_path.open('wb') as fp:
                        fp.write(ocr_text)
                yield vol_id, ocr_text.decode('utf8')
    else:
        for doc_path in base_path.glob('*.hocr'):
            yield doc_path.stem, doc_path.read_text()


def index_documents(docs):
    req = request.Request(
        "http://{}/solr/{}/update?softCommit=true".format(SOLR_HOST, SOLR_CORE),
        data=json.dumps(docs).encode('utf8'),
        headers={'Content-Type': 'application/json'})
    resp = request.urlopen(req)
    if resp.status >= 400:
        raise SolrException(json.loads(resp.read()), docs)


if __name__ == '__main__':
    ocr_iter = load_ocrtext(Path(GOOGLE1000_PATH))
    batch = []
    for idx, (ident, ocr) in enumerate(ocr_iter):
        doc = dict(id=ident, ocr_text=ocr)
        index_documents([doc])
        sys.stdout.write('\r{}/1000'.format(idx))
        sys.stdout.flush()
