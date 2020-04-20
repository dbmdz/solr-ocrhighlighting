#!/bin/bash
set -e

RELEASE_URL="https://api.github.com/repos/dbmdz/solr-ocrhighlighting/releases/latest"
PLUGIN_LIB_DIR="/var/solr/data/plugins"

wget -q https://github.com/stedolan/jq/releases/download/jq-1.6/jq-linux64 -O/tmp/jq
chmod +x /tmp/jq

mkdir -p "$PLUGIN_LIB_DIR"

if [ "$(ls /build |grep 'jar')" ]; then
    # Copy the plugin from the build directory
    echo "Installing plugin from build directory"
    cp /build/solr-ocrhighlighting-*.jar "$PLUGIN_LIB_DIR"/
else
    # Get the URL and filename for the latest release
    echo "Installing plugin from latest GitHub release"
    release_desc="$(curl -sS "$RELEASE_URL")"
    fname="$(echo "$release_desc" |/tmp/jq -r ".assets[0].name")"
    if [ ! -f "$PLUGIN_LIB_DIR"/"$fname" ]; then
        url="$(echo "$release_desc" |/tmp/jq -r ".assets[0].browser_download_url")"
        wget -q "$url" -P "$PLUGIN_LIB_DIR"
    fi
fi
