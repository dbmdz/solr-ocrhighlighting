#!/usr/bin/env python3
""" Utility to perform integration tests on OCR highlighting in different Solr
    versions, running in Docker containers.
"""

import difflib
import json
import os
import sys
from urllib import request
from urllib.parse import quote

SOLR_HOST = os.environ.get('SOLR_HOST', 'localhost')
HOCR_DOC = {"id": "test-hocr", "ocr_text": "/ocr-data/hocr.html"}
HOCR_SNIPS = [
    {
        "text": "rait par les bénéfices aux frais de l'expédition.Ce fut au mois de juin 1525 que partit <em>Francisco Pizarro de Panama</em> avec un navire et cent-vingt hommes.Nous le laisserons un instant pour revenir",
        "pages": [{"height": 2393, "id": "page_205", "width": 1476}],
        "regions": [
            {"ulx": 170, "uly": 446, "lrx": 1124, "lry": 796, "page": "page_205"}
        ],
        "highlights": [
            [
                {
                    "ulx": 0,
                    "uly": 150,
                    "lrx": 611,
                    "lry": 187,
                    "text": "Francisco Pizarro de Panama",
                    "page": "page_205",
                }
            ]
        ],
    }
]


ALTO_DOC = {
    "id": "test-alto",
    "ocr_text": "/ocr-data/alto1.xml[179626:179968,179973:180491,180496:180820,180825:181162,181167:191088,191093:214687,214692:261719,261724:267933,267938:310387,310392:352814]+/ocr-data/alto2.xml[1997:8611,8616:13294,13299:15243,15248:50042,50047:53793,53798:73482,73487:86667,86672:94241,94246:99808,99813:103087,103092:115141,115146:116775,116780:122549,122554:149762,149767:192789,192794:193502]",
}
ALTO_SNIPS = [
    {
        "text": "— Ce n'est rien, faisait Lisbeth ; c'est la charrette de <em>Hans Bockel</em> qui passe, ou bien c'est la mère Dreyfus qui s'en va maintenant à la veillée chez les Brêmer.",
        "pages": [{"height": 4790, "id": "P2", "width": 3140}],
        "regions": [{"ulx": 1621, "uly": 3710, "lrx": 2294, "lry": 3847, "page": "P2"}],
        "highlights": [
            [
                {
                    "ulx": 224,
                    "uly": 34,
                    "lrx": 423,
                    "lry": 67,
                    "text": "Hans Bockel",
                    "page": "P2",
                }
            ]
        ],
    }
]


class SolrException(Exception):
    def __init__(self, resp, payload):
        self.message = resp
        self.payload = payload


def index_documents(solr_port, docs):
    req = request.Request(
        "http://{}:{}/solr/ocr/update?softCommit=true".format(SOLR_HOST, solr_port),
        data=json.dumps(docs).encode("utf8"),
        headers={"Content-Type": "application/json"},
    )
    resp = request.urlopen(req)
    if resp.status >= 400:
        raise SolrException(json.loads(resp.read().decode('utf8')), docs)


def run_query(solr_port, query):
    req = request.Request(
        'http://{}:{}/solr/ocr/select?fl=id&hl=on&hl.ocr.fl=ocr_text&hl.weightMatches=true&q=ocr_text:"{}"'.format(
            SOLR_HOST, solr_port, quote(query)
        )
    )
    resp = request.urlopen(req)
    if resp.status >= 400:
        raise SolrException(json.loads(resp.read().decode('utf8')), None)
    return json.loads(resp.read().decode('utf8'))


def diff_snippets(fixture, actual):
    # Strip scores from snippets
    for snip in actual:
        if "score" in snip:
            del snip["score"]
    fixture_json = json.dumps(fixture, sort_keys=True, indent=2)
    actual_json = json.dumps(actual, sort_keys=True, indent=2)
    return "\n".join(
        difflib.unified_diff(fixture_json.split("\n"), actual_json.split("\n"))
    )


def main(solr_port):
    index_documents(solr_port, [HOCR_DOC, ALTO_DOC])

    test_error = False
    hocr_doc = run_query(solr_port, "Francisco Pizarro de Panama")
    hocr_diff = diff_snippets(
        HOCR_SNIPS, hocr_doc["ocrHighlighting"]["test-hocr"]["ocr_text"]["snippets"]
    )
    if hocr_diff:
        print("Test for hOCR highlighting failed!")
        print(hocr_diff)
        print("\n")
        test_error = True

    alto_doc = run_query(solr_port, "Hans Bockel")
    alto_diff = diff_snippets(
        ALTO_SNIPS, alto_doc["ocrHighlighting"]["test-alto"]["ocr_text"]["snippets"]
    )
    if alto_diff:
        print("Test for ALTO highlighting failed!")
        print(alto_diff)
        print("\n")
        test_error = True

    if test_error:
        print("INTEGRATION TESTS FAILED")
        sys.exit(1)
    else:
        print("INTEGRATION TESTS SUCCESSFUL")
        sys.exit(0)


if __name__ == "__main__":
    main(31337)
