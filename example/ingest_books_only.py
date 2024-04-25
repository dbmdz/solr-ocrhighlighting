#!/usr/bin/env python3
import json
import os
import re
import tarfile
from concurrent.futures import ProcessPoolExecutor, as_completed
from pathlib import Path
from urllib import request
from urllib.error import (
    URLError,
)
from typing import (
    Callable,
    Dict,
)


# turn on/off diagnostic information
LOG_LEVEL = 0
GOOGLE1000_PATH = './data/google1000'
GOOGLE1000_URL = 'https://ocrhl.jbaiter.de/data/google1000_texts.tar.gz'
GOOGLE1000_NUM_VOLUMES = 1000
GOOGLE1000_BATCH_SIZE = 4
LUNION_PATH = './data/bnl_lunion'
LUNION_TEXTS_URL = 'https://ocrhl.jbaiter.de/data/bnl_lunion_texts.tar.gz'
LUNION_NUM_ARTICLES = 41446
LUNION_BATCH_SIZE = 1000
SOLR_HOST = 'localhost:8983'
HOCR_METADATA_PAT = re.compile(
    r'<meta name=[\'"]DC\.(?P<key>.+?)[\'"] content=[\'"](?P<value>.+?)[\'"]\s*/?>')
NSMAP = {
    'mets': 'http://www.loc.gov/METS/',
    'mods': 'http://www.loc.gov/mods/v3'
}


class SolrException(Exception):
    def __init__(self, resp, payload):
        self.message = resp
        self.payload = payload


def gbooks_parse_metadata(hocr):
    # I know, the <center> won't hold, but I think it's okay in this case,
    # especially since we 100% know what data this script is going to work with
    # and we don't want an external lxml dependency in here
    raw_meta =  {key: int(value) if value.isdigit() else value
                 for key, value in HOCR_METADATA_PAT.findall(hocr)}
    return {
        'author': [raw_meta.get('creator')] if 'creator' in raw_meta else [],
        'title': [raw_meta['title']],
        'date': f"{raw_meta['date']}-01-01T00:00:00Z",
        **{k: v for k, v in raw_meta.items()
           if k not in ('creator', 'title', 'date')}
    }


def transform_gbook_to_document(document_path:Path) -> Dict:
    _content = document_path.read_text()
    _doc_id = document_path.stem.split("_")[1]
    _doc_name = document_path.name
    return {'id': _doc_id,
            'source': 'gbooks',
            'ocr_text': f'/data/google1000/{_doc_name}',
            **gbooks_parse_metadata(_content)}


def load_documents(the_url, base_path: Path, transform_func:Callable):
    try:
        with request.urlopen(the_url) as resp:
            try:
                tf = tarfile.open(fileobj=resp, mode='r|gz')
                for ti in tf:
                    if not ti.name.endswith('.hocr'):
                        continue
                    vol_id = ti.name.split('/')[-1].split('.')[0]
                    doc_path:Path = base_path / '{}.hocr'.format(vol_id)
                    if not doc_path.exists():
                        if LOG_LEVEL >= 1:
                            print(f"Download missing {doc_path}")
                        try:
                            ocr_text = tf.extractfile(ti).read()
                            with doc_path.open('wb') as fp:
                                fp.write(ocr_text)
                        except tarfile.ReadError as _entry_read_error:
                            print(f"ERROR processing {ti}: {_entry_read_error.args[0]}")
                            continue
                    if LOG_LEVEL >= 1:
                        print(f"Extract document data from {doc_path}")
                    yield transform_func(doc_path)
            except tarfile.ReadError as _tar_read_error:
                print(f"ERROR processing {tf}: {_tar_read_error.args[0]}")
    except URLError as _exc:
        print(f"ERROR request {the_url}: {_exc.args[0]}")


def index_documents(docs):
    _req_url = f"http://{SOLR_HOST}/solr/ocr/update?softCommit=true"
    try:
        if LOG_LEVEL >= 1:
            print(f"indexing {len(docs)} documents at {_req_url}")
        req = request.Request(_req_url,
            data=json.dumps(docs).encode('utf8'),
            headers={'Content-Type': 'application/json'})
        resp = request.urlopen(req)
        if resp.status >= 400:
            raise SolrException(json.loads(resp.read()), docs)
    except URLError as _url_err:
        print(f"ERROR indexing {len(docs)} last documents: {_url_err}")


def generate_batches(it, chunk_size):
    cur_batch = []
    for x in it:
        cur_batch.append(x)
        if len(cur_batch) == chunk_size:
            yield cur_batch
            cur_batch = []
    if cur_batch:
        yield cur_batch


if __name__ == '__main__':
    N_CORES = os.cpu_count() // 6 if os.cpu_count() is not None else 1
    with ProcessPoolExecutor(max_workers=N_CORES) as pool:
        print(f"\nLoad and Index Google {GOOGLE1000_NUM_VOLUMES} Books")
        futs = []
        BASE_PATH = Path(GOOGLE1000_PATH)
        BASE_PATH.mkdir(parents=True, exist_ok=True)
        gbooks_iter = load_documents(GOOGLE1000_URL, BASE_PATH, transform_gbook_to_document)
        for idx, batch in enumerate(generate_batches(gbooks_iter, GOOGLE1000_BATCH_SIZE)):
            futs.append(pool.submit(index_documents, batch))
            print(f"{((idx+1)*4):04}/{GOOGLE1000_NUM_VOLUMES}")
        for fut in as_completed(futs):
            fut.result()
    print("\n")
