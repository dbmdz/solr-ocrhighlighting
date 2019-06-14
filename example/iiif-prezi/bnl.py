import copy
from pathlib import Path

import lxml.etree as etree

from common import make_id, MANIFEST_TEMPLATE, CANVAS_TEMPLATE, NSMAP


def make_manifest(app, issue_id):
    base_dir = Path(app.config.get('BNL_PATH', '../data/bnl_lunion'))
    id_parts = issue_id[4:].split("_")
    issue_basename = f'{id_parts[0]}_newspaper_lunion_{id_parts[1]}'
    issue_path = base_dir / issue_basename
    protocol = app.config.get('PROTOCOL', 'http')
    location = app.config.get('SERVER_NAME', 'localhost:8008')
    app_path = app.config.get('APP_PATH', '')
    manifest_path = app.url_for('get_manifest', volume_id=issue_id)
    issue_search_path = app.url_for('search', doc_id=issue_id)
    image_api_base = app.config.get('IMAGE_API_BASE', 'http://localhost:8080')
    manifest = copy.deepcopy(MANIFEST_TEMPLATE)
    manifest['@id'] = f'{protocol}://{location}{app_path}/{manifest_path}'
    manifest['service']['@id'] = f'{protocol}://{location}{app_path}/{issue_search_path}'
    manifest['sequences'][0]['@id'] = make_id(app, issue_id, 'sequence')
    tree = etree.parse(f"{issue_path}/{issue_basename}-mets.xml")

    # Metadata
    meta_elem = tree.find(".//mets:dmdSec[@ID='MODSMD_PRINT']//mods:mods", namespaces=NSMAP)
    issue_meta = {
         'newspaper': meta_elem.findtext('.//mods:titleInfo/mods:title', namespaces=NSMAP),
         'title': meta_elem.findtext('.//mods:titleInfo/mods:partNumber', namespaces=NSMAP),
         'date': meta_elem.findtext('.//mods:dateIssued', namespaces=NSMAP),
         'publisher': meta_elem.findtext('.//mods:publisher', namespaces=NSMAP)}
    manifest['metadata'] = []
    if issue_meta['newspaper']:
        manifest['metadata'].append({'label': 'Newspaper Title', 'value': issue_meta['newspaper']})
    if issue_meta['title']:
        manifest['metadata'].append({'label': 'Issue Title', 'value': issue_meta['title']})
    if issue_meta['date']:
        manifest['metadata'].append({'label': 'Issue Date', 'value': issue_meta['date']})
    if issue_meta['publisher']:
        manifest['metadata'].append({'label': 'Publisher', 'value': issue_meta['publisher']})
    manifest['label'] = "{newspaper}: {title} ({date})".format(**issue_meta)
    search_path = app.url_for('search', doc_id=issue_id)
    manifest['service']['@id'] = f'{protocol}://{location}{app_path}/{search_path}'

    # Canvases
    for page_elem in tree.xpath(".//mets:structMap[@TYPE='PHYSICAL']//mets:div[@TYPE='PAGE']",
                                namespaces=NSMAP):
        file_id = next(i for i in page_elem.xpath(".//mets:area/@FILEID", namespaces=NSMAP)
                       if i.startswith('IMG'))
        adm_id = file_id.replace('IMG', 'IMGPARAM')
        img_elem = tree.find(f".//mets:amdSec[@ID='{adm_id}']//mix:BasicImageCharacteristics",
                             namespaces=NSMAP)
        img_width = int(img_elem.findtext('./mix:imageWidth', namespaces=NSMAP))
        img_height = int(img_elem.findtext('./mix:imageHeight', namespaces=NSMAP))
        img_fname = tree.xpath(f'.//mets:file[@ADMID="{adm_id}"]/mets:FLocat/@xlink:href',
                                namespaces=NSMAP)[0].split('/')[-1].split('.')[0]
        canvas = copy.deepcopy(CANVAS_TEMPLATE)
        page_id = page_elem.find('.//mets:area[@BETYPE="IDREF"]', namespaces=NSMAP).attrib['BEGIN']
        canvas['@id'] = f'{protocol}://{location}{app_path}/{issue_id}/canvas/{page_id}'
        image_url = f'{image_api_base}/{issue_id}_{img_fname.split("-")[-1]}'
        canvas['width'] = img_width
        canvas['height'] = img_height
        canvas['images'][0]['on'] = canvas['@id']
        canvas['images'][0]['resource']['width'] = img_width
        canvas['images'][0]['resource']['height'] = img_height
        canvas['images'][0]['resource']['@id'] = f'{image_url}/full/full/0/default.jpg'
        canvas['images'][0]['resource']['service']['@id'] = image_url
        manifest['sequences'][0]['canvases'].append(canvas)

    # Ranges
    # TODO: Yeah, we don't do nested ranges, since it's such a major PITA with
    #       Presentation API 2.0. Will do when 3.0 is stabilized
    manifest['structures'] = []
    alto_boxes = {k: v for d in (parse_alto(p) for p in (issue_path / 'text').glob("*.xml"))
                  for k, v in d.items()}
    toc_elems = tree.xpath(
        './/mets:structMap[@TYPE="LOGICAL"]//mets:div[@TYPE="ISSUE"]'
        '//mets:div[@TYPE="ARTICLE" or @TYPE="SECTION" or @TYPE="ADVERTISEMENT"]',
        namespaces=NSMAP)
    for elem in toc_elems:
        dtl_id = elem.attrib["ID"]
        range_id = f'{protocol}://{location}{app_path}/{issue_id}/range/{dtl_id}'
        iiif_range = {
            '@id': range_id,
            '@type': 'sc:Range',
            'canvases': []
        }
        if elem.attrib['TYPE'] == 'ARTICLE':
            article_id = elem.attrib['DMDID'].replace('MODSMD_ARTICLE', '')
            search_id = f'{issue_id}-{article_id}'
            search_path = app.url_for('search', doc_id=search_id)
            iiif_range['service'] = {
                "@context": "http://iiif.io/api/search/0/context.json",
                "@id": f'{protocol}://{location}{app_path}{search_path}',
                "profile": "http://iiif.io/api/search/0/search"
            }
        # TODO: If @TYPE='ARTICLE', add a search service for this article
        if 'LABEL' in elem.attrib:
            iiif_range['label'] = elem.attrib['LABEL']
        else:
            iiif_range['label'] = f"Unnamed {elem.attrib['TYPE'].lower()}"
        for area_elem in elem.findall('.//mets:area[@BETYPE="IDREF"]', namespaces=NSMAP):
            box_id = area_elem.attrib['BEGIN']
            canvas_idx = int(box_id.split("_")[0][1:]) - 1
            canvas_id = manifest['sequences'][0]['canvases'][canvas_idx]['@id']
            ocr_box = alto_boxes[box_id]
            canvas_region = f'#xywh={ocr_box[0]},{ocr_box[1]},{ocr_box[2]},{ocr_box[3]}'
            iiif_range['canvases'].append(canvas_id + canvas_region)
        manifest['structures'].append(iiif_range)
    manifest['structures'].insert(0, {
        '@id': f'{protocol}://{location}{app_path}/{issue_id}/range/top',
        '@type': 'sc:Range',
        'label': 'Table of Contents',
        'viewingHint': 'top',
        'ranges': [r['@id'] for r in manifest['structures']]
        })
    return manifest


def parse_alto(alto_path):
    tree = etree.parse(str(alto_path))
    attribs = ('HPOS', 'VPOS', 'WIDTH', 'HEIGHT')
    return { e.attrib['ID']: tuple(int(e.attrib[x]) for x in attribs)
             for e in tree.xpath('.//*[@ID and @HPOS and @VPOS and @WIDTH and @HEIGHT]')}