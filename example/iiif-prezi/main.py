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
MANIFEST_TEMPLATE = {
    "@id": None,
    "@context": "http://iiif.io/api/presentation/2/context.json",
    "@type": "sc:Manifest",
    "metadata": [],
    "attribution": "Provided by Google via Google Books 1000 dataset",
    "service": {
        "@context": "http://iiif.io/api/search/0/context.json",
        "@id": None,
        "profile": "http://iiif.io/api/search/0/search"
    },
    "sequences": [{
        "@id": None,
        "@type": "sc:Sequence",
        "label": "Current Page Order",
        "viewingDirection": "left-to-right",
        "viewingHint": "paged",
        "canvases": []}]}
CANVAS_TEMPLATE = {
    "@id": None,
    "@type": "sc:Canvas",
    "height": -1,
    "width": -1,
    "images": [
        {
        "@type": "oa:Annotation",
        "motivation": "sc:painting",
        "resource":{
            "@id": None,
            "@type": "dctypes:Image",
            "format": "image/jpeg",
            "service": {
                "@context": "http://iiif.io/api/image/2/context.json",
                "@id": None,
                "profile": "http://iiif.io/api/image/2/level1.json"
            },
            "height": None,
            "width": None,
        },
        "on": None}]}


app = Sanic(load_env="CFG_")


async def query_solr(query: str, volume_id: str):
    params = {
        'q': f'{query}',
        'df': 'ocr_text',
        'fq': 'id:' + volume_id,
        'hl': 'on',
        'hl.fl': 'ocr_text',
        'hl.snippets': 4096,
        'hl.weightMatches': 'true',
    }
    solr_url = app.config.get('SOLR_HANDLER', "http://127.0.0.1:8983/solr/ocrtest/select")
    async with app.aiohttp_session.get(solr_url, params=params) as resp:
        result_doc = await resp.json()
        ocr_hls = result_doc['ocrHighlighting']
        if volume_id in ocr_hls:
            return ocr_hls[volume_id]['ocr_text']
        else:
            return {
                'numTotal': 0,
                'snippets': [],
            }


def make_id(vol_id, resource_type="annotation"):
    protocol = app.config.get('PROTOCOL', 'http')
    location = app.config.get('SERVER_NAME', 'localhost:8008')
    app_path = app.config.get('APP_PATH', '')
    ident = re.sub('(.)([A-Z][a-z]+)', r'\1-\2', monsterurl.get_monster())
    ident = re.sub('([a-z0-9])([A-Z])', r'\1-\2', ident).replace('--', '-').lower()
    return f'{protocol}://{location}{app_path}/{vol_id}/{resource_type}/{ident}'


def make_contentsearch_response(hlresp, ignored_fields, vol_id, query):
    protocol = app.config.get('PROTOCOL', 'http')
    location = app.config.get('SERVER_NAME', 'localhost:8008')
    app_path = app.config.get('APP_PATH', '')
    search_path = app.url_for('search', volume_id=vol_id, q=query)
    doc = copy.deepcopy(RESPONSE_TEMPLATE)
    doc['@id'] = f'{protocol}://{location}{app_path}/{search_path}'
    doc['within']['total'] = hlresp['numTotal']
    doc['within']['ignored'] = ignored_fields
    for snip in hlresp['snippets']:
        text = snip['text'].replace('<em>', '').replace('</em>', '')
        for hl in snip['highlights']:
            hl_text = " ".join(b['text'] for b in hl)
            try:
                before = text[:text.index(hl_text)]
                after = text[text.index(hl_text) + len(hl_text):]
            except ValueError:
                before = after = None
            anno_ids = []
            for hlbox in hl:
                x = snip['region']['ulx'] + hlbox['ulx']
                y = snip['region']['uly'] + hlbox['uly']
                w = hlbox['lrx'] - hlbox['ulx']
                h = hlbox['lry'] - hlbox['uly']
                ident = make_id(vol_id)
                anno_ids.append(ident)
                anno = {
                    "@id": ident,
                    "@type": "oa:Annotation",
                    "motivation": "sc:painting",
                    "resource": {
                        "@type": "cnt:ContentAsText",
                        "chars": hlbox['text'] 
                    },
                    "on": f'{protocol}://{location}{app_path}/{vol_id}/canvas/{snip["page"]}#xywh={x},{y},{w},{h}'}
                doc['resources'].append(anno)
            doc['hits'].append({
                '@type': 'search:Hit',
                'annotations': anno_ids,
                'match': hl_text,
                'before': before,
                'after': after,
            })
    return doc


def make_manifest(vol_id, hocr_path):
    protocol = app.config.get('PROTOCOL', 'http')
    location = app.config.get('SERVER_NAME', 'localhost:8008')
    app_path = app.config.get('APP_PATH', '')
    manifest_path = app.url_for('get_manifest', volume_id=vol_id)
    search_path = app.url_for('search', volume_id=vol_id)
    image_api_base = app.config.get('IMAGE_API_BASE', 'http://localhost:8080')
    manifest = copy.deepcopy(MANIFEST_TEMPLATE)
    manifest['@id'] = f'{protocol}://{location}{app_path}/{manifest_path}'
    manifest['service']['@id'] = f'{protocol}://{location}{app_path}/{search_path}'
    manifest['sequences'][0]['@id'] = make_id(vol_id, 'sequence')
    tree = etree.parse(str(hocr_path))
    metadata = {}
    for meta_elem in tree.findall('.//meta'):
        if not meta_elem.attrib.get('name', '').startswith('DC.'):
            continue
        metadata[meta_elem.attrib['name'][3:]] = meta_elem.attrib['content']
    manifest['label'] = metadata.get('title', vol_id)
    manifest['metadata'] = [{'@label': k, '@value': v} for k, v in metadata.items()]
    for page_elem in tree.findall('.//div[@class="ocr_page"]'):
        canvas = copy.deepcopy(CANVAS_TEMPLATE)
        page_id = page_elem.attrib['id']
        canvas['@id'] = f'{protocol}://{location}{app_path}/{vol_id}/canvas/{page_id}'
        page_idx = int(page_id.split('_')[-1]) - 1
        image_url = f'{image_api_base}/{vol_id}/Image_{page_idx:04}.JPEG'
        _, _, width, height = (int(x) for x in page_elem.attrib['title'].split(' ')[1:])
        canvas['width'] = width
        canvas['height'] = height
        canvas['images'][0]['on'] = canvas['@id']
        canvas['images'][0]['resource']['width'] = width
        canvas['images'][0]['resource']['height'] = height
        canvas['images'][0]['resource']['@id'] = f'{image_url}/full/full/0/default.jpg'
        canvas['images'][0]['resource']['service']['@id'] = image_url
        manifest['sequences'][0]['canvases'].append(canvas)
    return manifest


@app.listener('before_server_start')
async def init(app, loop):
    app.aiohttp_session = aiohttp.ClientSession(loop=loop)


@app.listener('after_server_stop')
async def finish(app, loop):
    await app.aiohttp_session.close()


@app.route("/<volume_id>/search", methods=['GET', 'OPTIONS'])
async def search(request: Request, volume_id) -> HTTPResponse:
    query: str = request.args.get("q")
    resp = await query_solr(query, volume_id)
    ignored_params = [k for k in request.args.keys() if k != "q"]
    return json(make_contentsearch_response(
        resp, ignored_params, volume_id, query))


@app.route('/<volume_id>/manifest', methods=['GET', 'OPTIONS'])
async def get_manifest(request, volume_id):
    hocr_path = Path(app.config.get('GOOGLE1000_PATH', '../google1000')) / f'{volume_id}.hocr'
    return json(make_manifest(volume_id, hocr_path))


if __name__ == "__main__":
    port = 8008
    debug = False
    if len(sys.argv) >= 2:
        port = int(sys.argv[1])
    if len(sys.argv) == 3:
        debug = sys.argv[2] == 'debug'
    app.run(host="0.0.0.0", port=port, debug=debug)