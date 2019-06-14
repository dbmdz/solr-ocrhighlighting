import copy
from pathlib import Path

import lxml.etree as etree

from common import make_id, MANIFEST_TEMPLATE, CANVAS_TEMPLATE


def make_manifest(app, vol_id):
    base_dir = Path(app.config.get('GOOGLE1000_PATH', '../data/google1000'))
    hocr_path =  base_dir / f'Volume_{vol_id.split(":")[1]}.hocr'
    protocol = app.config.get('PROTOCOL', 'http')
    location = app.config.get('SERVER_NAME', 'localhost:8008')
    app_path = app.config.get('APP_PATH', '')
    manifest_path = app.url_for('get_manifest', volume_id=vol_id)
    search_path = app.url_for('search', doc_id=vol_id)
    image_api_base = app.config.get('IMAGE_API_BASE', 'http://localhost:8080')
    manifest = copy.deepcopy(MANIFEST_TEMPLATE)
    manifest['@id'] = f'{protocol}://{location}{app_path}/{manifest_path}'
    manifest['service']['@id'] = f'{protocol}://{location}{app_path}{search_path}'
    manifest['sequences'][0]['@id'] = make_id(app, vol_id, 'sequence')
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
        image_url = f'{image_api_base}/{vol_id}_{page_idx:04}'
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