import copy
import re
import sys
from functools import wraps
from pathlib import Path

import aiohttp
import lxml.etree as etree
import monsterurl
from sanic import Sanic
from sanic.request import Request
from sanic.response import json, HTTPResponse

import bnl
import common
import gbooks

# The BNL ALTO encodes coordinates as 1/10mm values.
# To convert to pixels, we divide the dots-per-inch of the image (300 in both
# horizontal and vertical direction for all images in the corpus) by the number
# of 1/10mm units in an inch
BNL_10MM_TO_PIX_FACTOR = 300 / 254

HL_PAT = re.compile("<em>(.+?)</em>")
GBOOKS_PAT = re.compile(r'gbooks:\d{4}') 
BNL_ISSUE_PAT = re.compile(r'bnl:\d{7}_\d{4}-\d{2}-\d{2}')
BNL_ARTICLE_PAT = re.compile(r'bnl:\d{7}_\d{4}-\d{2}-\d{2}-\d+')
RESPONSE_TEMPLATE = {
  "@context":[
      "http://iiif.io/api/presentation/2/context.json",
      "http://iiif.io/api/search/0/context.json"
  ],
  "@type":"sc:AnnotationList",

  "within": {
    "@type": "sc:Layer"
  },

  "resources": [],
  "hits": []
}


app = Sanic(load_env="CFG_", name="ocrhl-iiif-prezi")


async def query_solr(query: str, fq: str):
    params = {
        'q': f'{query}',
        'df': 'ocr_text',
        'fq': fq,
        'rows': 500,
        'hl': 'on',
        'hl.ocr.fl': 'ocr_text',
        'hl.snippets': 4096,
        'hl.weightMatches': 'true',
    }
    solr_base = app.config.get('SOLR_BASE', "http://127.0.0.1:8983/solr")
    solr_url = f"{solr_base}/ocr/select"
    async with app.aiohttp_session.get(solr_url, params=params) as resp:
        result_doc = await resp.json()
        ocr_hls = result_doc['ocrHighlighting']
        out = {
            'numTotal': 0,
            'snippets': []
        }
        for page_snips in ocr_hls.values():
            snips = page_snips['ocr_text']['snippets']
            out['snippets'].extend(snips)
            out['numTotal'] += page_snips['ocr_text']['numTotal']
        return out


def make_contentsearch_response(hlresp, ignored_fields, vol_id, query, is_bnl):
    protocol = app.config.get('PROTOCOL', 'http')
    location = app.config.get('SERVER_NAME', 'localhost:8008')
    app_path = app.config.get('APP_PATH', '')
    search_path = app.url_for('search', doc_id=vol_id, q=query)
    doc = copy.deepcopy(RESPONSE_TEMPLATE)
    doc['@id'] = f'{protocol}://{location}{app_path}/{search_path}'
    doc['within']['total'] = hlresp['numTotal']
    doc['within']['ignored'] = ignored_fields
    for snip in hlresp['snippets']:
        text = snip['text']
        hl_textmatches = list(HL_PAT.finditer(text))
        for idx, hlspan in enumerate(snip['highlights']):
            hl_match = hl_textmatches[idx]
            try:
                before = text[:hl_match.start()].replace('<em>', '').replace('</em>', '')
                after = text[hl_match.end():].replace('<em>', '').replace('</em>', '')
            except ValueError:
                before = after = None
            hl_text = hl_match.group(1)
            anno_ids = []
            for hlbox in hlspan:
                region = snip['regions'][hlbox['parentRegionIdx']]
                page = snip['pages'][region['pageIdx']]['id']
                x = region['ulx'] + hlbox['ulx']
                y = region['uly'] + hlbox['uly']
                w = hlbox['lrx'] - hlbox['ulx']
                h = hlbox['lry'] - hlbox['uly']
                if is_bnl:
                    x = int(x * BNL_10MM_TO_PIX_FACTOR)
                    y = int(y * BNL_10MM_TO_PIX_FACTOR)
                    w = int(w * BNL_10MM_TO_PIX_FACTOR)
                    h = int(h * BNL_10MM_TO_PIX_FACTOR)
                ident = common.make_id(app, vol_id)
                anno_ids.append(ident)
                anno = {
                    "@id": ident,
                    "@type": "oa:Annotation",
                    "motivation": "sc:painting",
                    "resource": {
                        "@type": "cnt:ContentAsText",
                        "chars": hlbox['text'] 
                    },
                    "on": f'{protocol}://{location}{app_path}/{vol_id}/canvas/{page}#xywh={x},{y},{w},{h}'}
                doc['resources'].append(anno)
            doc['hits'].append({
                '@type': 'search:Hit',
                'annotations': anno_ids,
                'match': hl_text,
                'before': before,
                'after': after,
            })
    return doc


@app.listener('before_server_start')
async def init(app, loop):
    app.aiohttp_session = aiohttp.ClientSession(loop=loop)


@app.listener('after_server_stop')
async def finish(app, loop):
    await app.aiohttp_session.close()


@app.route("/<doc_id>/search", methods=['GET', 'OPTIONS'])
async def search(request: Request, doc_id) -> HTTPResponse:
    query: str = request.args.get("q")
    if GBOOKS_PAT.match(doc_id) or BNL_ARTICLE_PAT.match(doc_id):
        fq = f'id:{doc_id.split(":")[1]}'
    elif BNL_ISSUE_PAT.match(doc_id):
        fq = f'issue_id:{doc_id.split(":")[1]}'
    resp = await query_solr(query, fq)
    ignored_params = [k for k in request.args.keys() if k != "q"]
    return json(make_contentsearch_response(
        resp, ignored_params, doc_id, query,
        BNL_ARTICLE_PAT.match(doc_id) or BNL_ISSUE_PAT.match(doc_id)))


@app.route('/<volume_id>/manifest', methods=['GET', 'OPTIONS'])
async def get_manifest(request, volume_id):
    if GBOOKS_PAT.match(volume_id):
        return json(gbooks.make_manifest(app, volume_id))
    elif BNL_ISSUE_PAT.match(volume_id):
        return json(bnl.make_manifest(app, volume_id))
    else:
        return json({'error': 'Unknown identifier'}, status=404)


if __name__ == "__main__":
    port = 8008
    debug = False
    if len(sys.argv) >= 2:
        port = int(sys.argv[1])
    if len(sys.argv) == 3:
        debug = sys.argv[2] == 'debug'
    app.run(host="0.0.0.0", port=port, debug=debug)
