#!/usr/bin/env python3
import itertools
import json
import re
import sys
import tarfile
import xml.etree.ElementTree as etree
from concurrent.futures import ProcessPoolExecutor, as_completed
from pathlib import Path
from urllib import request


GOOGLE1000_PATH = './data/google1000'
GOOGLE1000_URL = 'https://ocrhl.jbaiter.de/data/gbooks1000_hocr.tar.xz'
GOOGLE1000_NUM_VOLUMES = 1000
LUNION_PATH = './data/bnl_lunion'
LUNION_TEXTS_URL = 'https://ocrhl.jbaiter.de/data/bnl_lunion_texts.tar.gz'
LUNION_NUM_ARTICLES = 41446
SOLR_HOST = 'localhost:8983'
HOCR_METADATA_PAT = re.compile(
    r'<meta name="DC\.(?P<key>.+?)" content="(?P<value>.+?)"\s*/>')
NSMAP = {
    'mets': 'http://www.loc.gov/METS/',
    'mods': 'http://www.loc.gov/mods/v3'
}


class SolrException(Exception):
    def __init__(self, resp, payload):
        self.message = resp
        self.payload = payload


def gbooks_are_volumes_missing(base_path):
    for vol_no in range(1000):
        vol_path = base_path / 'Volume_{:04}.hocr'.format(vol_no)
        if not vol_path.exists():
            return True
    return False


def gbooks_parse_metadata(hocr):
    # I know, the <center> won't hold, but I think it's okay in this case,
    # especially since we 100% know what data this script is going to work with
    # and we don't want an external lxml dependency in here
    raw_meta =  {key: int(value) if value.isdigit() else value
                 for key, value in HOCR_METADATA_PAT.findall(hocr)}
    return {
        'author': [raw_meta.get('creator')] if 'creator' in raw_meta else [],
        'title': [raw_meta['title']],
        'date': '{}-01-01T00:00:00Z'.format(raw_meta['date']),
        **{k: v for k, v in raw_meta.items()
           if k not in ('creator', 'title', 'date')}
    }


def gbooks_load_documents(base_path):
    if gbooks_are_volumes_missing(base_path):
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
                yield {'id': vol_id.split("_")[-1],
                       'ocr_text': '/data/google1000/' + doc_path.name,
                       **gbooks_parse_metadata(hocr)}
    else:
        for doc_path in base_path.glob('*.hocr'):
            hocr = doc_path.read_text()
            yield {'id': doc_path.stem.split("_")[1],
                   'ocr_text': '/data/google1000/' + doc_path.name,
                   **gbooks_parse_metadata(hocr)}


def bnl_get_metadata(mods_tree):
    authors = []
    name_elems = mods_tree.findall('.//mods:name', namespaces=NSMAP)
    for name_elem in name_elems:
        role = name_elem.findtext('.//mods:roleTerm', namespaces=NSMAP)
        if role == 'aut':
            authors.append(" ".join(e.text for e in 
                name_elem.findall('.//mods:namePart', namespaces=NSMAP)))
    return {
        'author': authors,
        'title': [e.text for e in mods_tree.findall(".//mods:title",
                                                    namespaces=NSMAP)],
        'subtitle': [e.text for e in mods_tree.findall(".//mods:subTitle",
                                                       namespaces=NSMAP)]
    }


def bnl_get_article_pointer(path_regions):
    grouped = {
        p: sorted(bid for bid, _ in bids)
        for p, bids in itertools.groupby(path_regions, key=lambda x: x[1])}
    pointer_parts = []
    for page_path, block_ids in grouped.items():
        local_path = Path(LUNION_PATH) / page_path
        regions = []
        with local_path.open('rb') as fp:
            page_bytes = fp.read()
        for block_id in block_ids:
            start_match = re.search(
                rb'<([A-Za-z]+?) ID="%b"' % (block_id.encode()), page_bytes)
            start = start_match.start()
            end_tag = b'</%b>' % (start_match.group(1),)
            end = page_bytes.index(end_tag, start) + len(end_tag)
            regions.append((start, end))
        pointer_parts.append(
            '/data/bnl_lunion/{}[{}]'.format(
                page_path,
                ','.join('{}:{}'.format(*r) for r in sorted(regions))))
    return '+'.join(pointer_parts)


def bnl_extract_article_docs(issue_id, mets_tree, alto_basedir):
    ocr_paths = sorted(alto_basedir.glob("*.xml"))
    article_elems = mets_tree.findall(
        ".//mets:structMap[@TYPE='LOGICAL']//mets:div[@TYPE='ARTICLE']",
        namespaces=NSMAP)
    title_info = mets_tree.find(
        ".//mets:dmdSec[@ID='MODSMD_PRINT']//mods:titleInfo",
        namespaces=NSMAP)
    newspaper_title = title_info.findtext('./mods:title', namespaces=NSMAP)
    newspaper_part = title_info.findtext('./mods:partNumber', namespaces=NSMAP)
    file_mapping = {
        e.attrib['ID']: next(iter(e)).attrib['{http://www.w3.org/1999/xlink}href'][9:]
        for e in mets_tree.findall('.//mets:fileGrp[@USE="Text"]/mets:file',
                                   namespaces=NSMAP)}
    for elem in article_elems:
        meta_id = elem.attrib['DMDID']
        path_regions = [
            (e.attrib['BEGIN'],
             alto_basedir.parent.name + '/' + file_mapping.get(e.attrib['FILEID']))
            for e in elem.findall('.//mets:fptr//mets:area',
                                  namespaces=NSMAP)]
        mods_meta = mets_tree.find(
            './/mets:dmdSec[@ID="{}"]//mods:mods'.format(meta_id),
            namespaces=NSMAP)
        issue_date = mets_tree.findtext('.//mods:dateIssued', namespaces=NSMAP)
        article_no = meta_id.replace("MODSMD_ARTICLE", "")
        yield {
            'id': '{}-{}'.format(issue_id, article_no),
            'issue_id': issue_id,
            'date': issue_date + 'T00:00:00Z',
            'newspaper_title': newspaper_title,
            'newspaper_part': newspaper_part,
            'ocr_text': bnl_get_article_pointer(path_regions),
            **bnl_get_metadata(mods_meta),
        }


def bnl_are_volumes_missing(base_path):
    num_pages = sum(1 for _ in base_path.glob("*/text/*.xml"))
    return num_pages != 10880


def bnl_load_documents(base_path):
    if not base_path.exists():
        base_path.mkdir()
    if bnl_are_volumes_missing(base_path):
        print("Downloading missing BNL/L'Union issues to {}".format(base_path))
        base_path.mkdir(exist_ok=True)
        with request.urlopen(LUNION_TEXTS_URL) as resp:
            tf = tarfile.open(fileobj=resp, mode='r|gz')
            last_vol = None
            for ti in tf:
                if ti.isdir() and '/' not in ti.name:
                    if last_vol is not None:
                        vol_path = base_path / last_vol
                        mets_path = next(iter(vol_path.glob("*-mets.xml")))
                        vol_id = last_vol.replace("newspaper_lunion_", "")
                        yield from bnl_extract_article_docs(
                            vol_id, etree.parse(str(mets_path)),
                            vol_path / 'text')
                    last_vol = ti.name
                if ti.isdir():
                    (base_path / ti.name).mkdir(parents=True, exist_ok=True)
                else:
                    out_path = base_path / ti.name
                    with out_path.open('wb') as fp:
                        fp.write(tf.extractfile(ti).read())
            vol_path = base_path / last_vol
            mets_path = next(iter(vol_path.glob("*-mets.xml")))
            vol_id = last_vol.replace("newspaper_lunion_", "")
            yield from bnl_extract_article_docs(
                vol_id, etree.parse(str(mets_path)),
                vol_path / 'text')
    else:
        for issue_dir in base_path.iterdir():
            if not issue_dir.is_dir() or not issue_dir.name.startswith('15'):
                continue
            mets_path = next(iter(issue_dir.glob("*-mets.xml")))
            vol_id = issue_dir.name.replace("newspaper_lunion_", "")
            yield from bnl_extract_article_docs(
                vol_id, etree.parse(str(mets_path)),
                issue_dir / 'text')


def index_documents(docs):
    req = request.Request(
        "http://{}/solr/ocr/update?softCommit=true".format(SOLR_HOST),
        data=json.dumps(docs).encode('utf8'),
        headers={'Content-Type': 'application/json'})
    resp = request.urlopen(req)
    if resp.status >= 400:
        raise SolrException(json.loads(resp.read()), docs)


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
    with ProcessPoolExecutor(max_workers=8) as pool:
        print("Indexing BNL/L'Union articles")
        futs = []
        bnl_iter = bnl_load_documents(Path(LUNION_PATH))
        for idx, batch in enumerate(generate_batches(bnl_iter, 100)):
            futs.append(pool.submit(index_documents, batch))
            print("\r{:05}/{}".format((idx+1)*100, LUNION_NUM_ARTICLES), end='')
        for fut in as_completed(futs):
            fut.result()
        print("\nIndexing Google 1000 Books volumes")
        futs = []
        gbooks_iter = gbooks_load_documents(Path(GOOGLE1000_PATH))
        for idx, batch in enumerate(generate_batches(gbooks_iter, 4)):
            futs.append(pool.submit(index_documents, batch))
            print("\r{:04}/{}".format((idx+1)*4, GOOGLE1000_NUM_VOLUMES), end='')
        for fut in as_completed(futs):
            fut.result()
    print("\n")
