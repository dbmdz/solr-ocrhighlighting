import re

import monsterurl


NSMAP = {
    'mets': 'http://www.loc.gov/METS/',
    'mods': 'http://www.loc.gov/mods/v3',
    'mix': 'http://www.loc.gov/mix/v20',
    'xlink': 'http://www.w3.org/1999/xlink',
    'alto': 'http://www.loc.gov/standards/alto/ns-v3#'
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


def make_id(app, vol_id, resource_type="annotation"):
    protocol = app.config.get('PROTOCOL', 'http')
    location = app.config.get('SERVER_NAME', 'localhost:8008')
    app_path = app.config.get('APP_PATH', '')
    ident = re.sub('(.)([A-Z][a-z]+)', r'\1-\2', monsterurl.get_monster())
    ident = re.sub('([a-z0-9])([A-Z])', r'\1-\2', ident).replace('--', '-').lower()
    return f'{protocol}://{location}{app_path}/{vol_id}/{resource_type}/{ident}'

