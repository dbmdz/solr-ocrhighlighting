#!/bin/bash
set -e

function extract_json() {
    path="$1"
    python -c "import json, sys; print json.load(sys.stdin)$path"
}

RELEASE_URL="https://api.github.com/repos/dbmdz/solr-ocrhighlighting/releases/latest"
PLUGIN_LIB_DIR="/var/solr/data/plugins"

mkdir -p "$PLUGIN_LIB_DIR"

# Get the URL and filename for the latest release
release_desc="$(curl -sS "$RELEASE_URL")"
fname="$(echo "$release_desc" |extract_json "['assets'][0]['name']")"
if [ ! -f "$PLUGIN_LIB_DIR"/"$fname" ]; then
    url="$(echo "$release_desc" |extract_json "['assets'][0]['browser_download_url']")"
    wget "$url" -P "$PLUGIN_LIB_DIR"
fi
