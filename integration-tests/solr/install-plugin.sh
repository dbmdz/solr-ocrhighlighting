#!/bin/bash
set -e

PLUGIN_LIB_DIR="/var/solr/data/plugins"
BUILD_PATH="/build"

mkdir -p "$PLUGIN_LIB_DIR"
cp "$BUILD_PATH"/solr-ocrhighlighting*.jar "$PLUGIN_LIB_DIR"