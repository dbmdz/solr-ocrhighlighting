#!/usr/bin/env python3
import json
import re
import sys
import tarfile
from pathlib import Path
from urllib import request


GOOGLE1000_PATH = './google1000'
GOOGLE1000_URL = 'https://ocrhl.jbaiter.de/data/gbooks1000_hocr.tar.xz'
SOLR_HOST = 'localhost:8983'
SOLR_CORE = 'ocrtest'

METADATA_PAT = re.compile(
    r'<meta name="DC\.(?P<key>.+?)" content="(?P<value>.+?)"\s*/>')


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


def parse_metadata(hocr):
    # I know, the <center> won't hold, but I think it's okay in this case,
    # especially since we 100% know what data this script is going to work with
    # and we don't want an external lxml dependency in here
    return {key: int(value) if value.isdigit() else value
            for key, value in METADATA_PAT.findall(hocr)}


def load_documents(base_path):
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
                hocr = ocr_text.decode('utf8')
                yield {'id': vol_id, 'ocr_text': hocr,
                       **parse_metadata(hocr)}
    else:
        for doc_path in base_path.glob('*.hocr'):
            hocr = doc_path.read_text()
            yield {'id': doc_path.stem, 'ocr_text': hocr,
                   **parse_metadata(hocr)}


def index_documents(docs):
    req = request.Request(
        "http://{}/solr/{}/update?softCommit=true".format(SOLR_HOST, SOLR_CORE),
        data=json.dumps(docs).encode('utf8'),
        headers={'Content-Type': 'application/json'})
    resp = request.urlopen(req)
    if resp.status >= 400:
        raise SolrException(json.loads(resp.read()), docs)


if __name__ == '__main__':
    doc_iter = load_documents(Path(GOOGLE1000_PATH))
    batch = []
    for idx, doc in enumerate(doc_iter):
        index_documents([doc])
        sys.stdout.write('\r{}/1000'.format(idx))
        sys.stdout.flush()
