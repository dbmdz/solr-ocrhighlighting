#!/bin/bash
set -e

PLUGIN_LIB_DIR="/var/solr/data/plugins"
BUILD_PATH="/build"

mkdir -p "$PLUGIN_LIB_DIR"
cp "$BUILD_PATH"/*.jar "$PLUGIN_LIB_DIR"