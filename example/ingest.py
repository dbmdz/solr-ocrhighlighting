#!/usr/bin/env python3
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
LUNIN_NUM_ARTICLES = 41446
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
    return {key: int(value) if value.isdigit() else value
            for key, value in HOCR_METADATA_PAT.findall(hocr)}


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
                yield {'id': vol_id.split("_")[-1], 'ocr_text': hocr,
                       **gbooks_parse_metadata(hocr)}
    else:
        for doc_path in base_path.glob('*.hocr'):
            hocr = doc_path.read_text()
            yield {'id': doc_path.stem.split("_")[1],
                   'ocr_text': hocr,
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


def bnl_mask_ocr_text(ocr_text, block_ids):
    masked_text = []
    masking_start = 0
    for bid in sorted(block_ids, key=lambda i: ocr_text.index(i)):
        start_match = re.search(r'<([A-Za-z]+?) ID="{bid}"'.format(bid=bid),
                                ocr_text)
        start = start_match.start()
        if start < masking_start:
            raise Exception("block_ids were out of order, aborting.")
        end_pat = re.compile(r'</{tag}>'.format(tag=start_match.group(1)))
        end_match = end_pat.search(ocr_text, pos=start)
        end = end_match.end()
        padding = (start - masking_start) * " "
        masked_text.append(padding)
        masked_text.append(ocr_text[start:end])
        masking_start = end
    return "".join(masked_text)


def bnl_extract_article_docs(issue_id, mets_tree, alto_basedir):
    ocr_text = "".join(p.read_text()
                       for p in sorted(alto_basedir.glob("*.xml")))
    article_elems = mets_tree.findall(
        ".//mets:structMap[@TYPE='LOGICAL']//mets:div[@TYPE='ARTICLE']",
        namespaces=NSMAP)
    title_info = mets_tree.find(
        ".//mets:dmdSec[@ID='MODSMD_PRINT']//mods:titleInfo",
        namespaces=NSMAP)
    newspaper_title = title_info.findtext('./mods:title', namespaces=NSMAP)
    newspaper_part = title_info.findtext('./mods:partNumber', namespaces=NSMAP)
    for elem in article_elems:
        meta_id = elem.attrib['DMDID']
        block_ids = [e.attrib['BEGIN'] for e in elem.findall('.//mets:fptr//mets:area',
                                                             namespaces=NSMAP)]
        mods_meta = mets_tree.find(
            f'.//mets:dmdSec[@ID="{meta_id}"]//mods:mods',
            namespaces=NSMAP)
        masked_text = bnl_mask_ocr_text(ocr_text, block_ids)
        issue_date = mets_tree.findtext('.//mods:dateIssued', namespaces=NSMAP)
        article_no = meta_id.replace("MODSMD_ARTICLE", "")
        yield {
            'id': f'{issue_id}-{article_no}',
            'issue_id': issue_id,
            'date': issue_date + 'T00:00:00Z',
            'newspaper_title': newspaper_title,
            'newspaper_part': newspaper_part,
            'ocr_text': masked_text,
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


def index_documents(core_name, docs):
    req = request.Request(
        "http://{}/solr/{}/update?softCommit=true".format(SOLR_HOST, core_name),
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


if __name__ == '__main__':
    with ProcessPoolExecutor(max_workers=8) as pool:
        print("Indexing BNL/L'Union articles")
        futs = []
        bnl_iter = bnl_load_documents(Path(LUNION_PATH))
        for idx, batch in enumerate(generate_batches(bnl_iter, 100)):
            futs.append(pool.submit(index_documents, 'bnl_lunion', batch))
            print(f"\r{(idx+1)*100:05}/{LUNIN_NUM_ARTICLES}", end='')
        for fut in as_completed(futs):
            fut.result()
        print("\nIndexing Google 1000 Books volumes")
        futs = []
        gbooks_iter = gbooks_load_documents(Path(GOOGLE1000_PATH))
        for idx, batch in enumerate(generate_batches(gbooks_iter, 4)):
            futs.append(pool.submit(index_documents, 'google1000', batch))
            print(f"\r{(idx+1)*4:04}/{GOOGLE1000_NUM_VOLUMES}", end='')
        for fut in as_completed(futs):
            fut.result()
    print("\n")