#!/bin/bash
set -e
SOLR_HOST="${SOLR_HOST:-localhost}"
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null 2>&1 && pwd )"
SOLR7_VERSIONS="7.6 7.5"
SOLR8_VERSIONS="8.7 8.6 8.5 8.4 8.3 8.2 8.1"

wait_for_solr() {
    while [[ "$(curl -s -o /dev/null http://$SOLR_HOST:31337/solr/ocr/select -w '%{http_code}')" != "200" ]]; do
        sleep 3;
    done
}

# Make sure we're in the test directory
cd $SCRIPT_DIR

find ../target

# Solr 8 versions
for version in $SOLR8_VERSIONS; do
    echo "Testing $version"
    container_name="ocrhltest-$version"
    docker run \
        --name "$container_name" \
        -e SOLR_LOG_LEVEL=ERROR \
        -v "$(pwd)/solr/install-plugin.sh:/docker-entrypoint-initdb.d/install-plugin.sh" \
        -v "$(pwd)/solr/core/v8:/opt/core-config" \
        -v "$(pwd)/data:/ocr-data" \
        -v "$(realpath ..)/target:/build" \
        -p "31337:8983" \
        solr:$version \
        solr-precreate ocr /opt/core-config & \
    wait_for_solr
    python3 test.py
    docker stop "$container_name" > /dev/null
    docker rm "$container_name" > /dev/null
done

# Solr 7 has a different Docker setup
for version in $SOLR7_VERSIONS; do
    echo "Testing $version"
    docker run \
        --name "ocrhltest-$version" \
        -e SOLR_LOG_LEVEL=ERROR \
        -v "$(pwd)/solr/install-plugin-v7.sh:/docker-entrypoint-initdb.d/install-plugin-v7.sh" \
        -v "$(pwd)/solr/core/v7:/opt/core-config" \
        -v "$(pwd)/data:/ocr-data" \
        -v "$(realpath ..)/target:/build" \
        -p "31337:8983" \
        solr:$version \
        solr-precreate ocr /opt/core-config & \
    wait_for_solr
    python3 test.py
    docker stop "ocrhltest-$version" > /dev/null
    docker rm "ocrhltest-$version" > /dev/null
done

echo "INTEGRATION TESTS SUCCEEDED"