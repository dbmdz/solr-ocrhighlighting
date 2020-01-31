#!/bin/bash
set -e

PLUGIN_LIB_DIR="/opt/solr/contrib/ocrsearch/lib"
BUILD_PATH="/build"

mkdir -p "$PLUGIN_LIB_DIR"
cp "$BUILD_PATH"/*.jar "$PLUGIN_LIB_DIR"