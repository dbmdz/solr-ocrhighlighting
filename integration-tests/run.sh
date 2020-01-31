#!/bin/bash
set -e
SOLR7_VERSIONS="7.6 7.5"
SOLR8_VERSIONS="latest 8.3 8.2 8.1"

wait_for_solr() {
    while [[ "$(curl -s -o /dev/null http://localhost:31337/solr/ocr/select -w '%{http_code}')" != "200" ]]; do
        sleep 1;
    done
}

# Solr 8 versions
for version in $SOLR8_VERSIONS; do
    echo "Testing $version"
    container_name="ocrhltest-$version"
    docker run \
        -d \
        --name "$container_name" \
        -v "$(pwd)/solr/install-plugin.sh:/docker-entrypoint-initdb.d/install-plugin.sh" \
        -v "$(pwd)/solr/core/v8:/opt/core-config" \
        -v "$(pwd)/data:/ocr-data" \
        -v "$(realpath ..)/target:/build" \
        -p "31337:8983" \
        solr:$version \
        solr-precreate ocr /opt/core-config > /dev/null
    wait_for_solr
    python3 test.py
    docker stop "$container_name" > /dev/null
    docker rm "$container_name" > /dev/null
done

# Solr 7 has a different Docker setup
for version in $SOLR7_VERSIONS; do
    echo "Testing $version"
    docker run \
        -d \
        --name "ocrhltest-$version" \
        -v "$(pwd)/solr/install-plugin-v7.sh:/docker-entrypoint-initdb.d/install-plugin-v7.sh" \
        -v "$(pwd)/solr/core/v7:/opt/core-config" \
        -v "$(pwd)/data:/ocr-data" \
        -v "$(realpath ..)/target:/build" \
        -p "31337:8983" \
        solr:$version \
        solr-precreate ocr /opt/core-config > /dev/null
    wait_for_solr
    python3 test.py
    docker stop "ocrhltest-$version" > /dev/null
    docker rm "ocrhltest-$version" > /dev/null
done