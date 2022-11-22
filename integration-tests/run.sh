#!/bin/bash
set -e
SOLR_HOST="${SOLR_HOST:-localhost}"
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null 2>&1 && pwd )"
SOLR7_VERSIONS="7.7 7.6 7.5"
SOLR8_VERSIONS="8.11 8.10 8.9 8.8 8.7 8.6 8.5 8.4 8.3 8.2 8.1 8.0"
SOLR9_VERSIONS="9.1 9.0"
SOLR78_PLUGIN_PATH=/tmp/solrocr-solr78

wait_for_solr() {
    status="404"
    while [[ "$status" != "200" ]]; do
        set +e
        status="$(curl -s -o /dev/null http://$SOLR_HOST:31337/solr/ocr/select -w '%{http_code}')"
        if [[ "$status" == "500" ]]; then
            docker logs $1
            exit 1
        fi
        sleep 3;
    done
    set -e
}

# Make sure we're in the test directory
cd $SCRIPT_DIR

if [ ! -d "../target" ]; then
    echo "Please run 'mvn clean package' in the parent directory first!"
    exit 1
fi

solr9_jar="$(ls ../target/*.jar |egrep -v '(javadoc|original|source|solr78)')"
solr78_jar="$(ls ../target/*.jar |egrep 'solr78.jar')"
if [ -z "$solr78_jar" ]; then
    echo "No solr78 jar found in ../target, please run 'util/patch_solr78_bytecode.py' in the parent directory first!"
    exit 1
fi

plugin_dir="$(mktemp -d)"
cp $solr9_jar "$plugin_dir"
chmod -R a+rwx "$plugin_dir"
for version in $SOLR9_VERSIONS; do
    printf "Testing $version: "
    container_name="ocrhltest-$version"
    docker run \
    --name "$container_name" \
    -e SOLR_LOG_LEVEL=ERROR \
    -v "$(pwd)/solr/install-plugin.sh:/docker-entrypoint-initdb.d/install-plugin.sh" \
    -v "$(pwd)/solr/core/v9:/opt/core-config" \
    -v "$(pwd)/data:/ocr-data" \
    -v "$plugin_dir:/build" \
    -p "31337:8983" \
    solr:$version \
    solr-precreate ocr /opt/core-config > /dev/null 2>&1 & \
    wait_for_solr "$container_name"
    if ! python3 test.py; then
        printf " !!!FAIL!!!\n"
        docker logs
    else
        printf " OK\n"
    fi
    docker stop "$container_name" > /dev/null
    docker rm "$container_name" > /dev/null
done

rm -rf "$plugin_dir"/*.jar
cp $solr78_jar "$plugin_dir"
# Solr 8 versions, use a different plugin JAR
for version in $SOLR8_VERSIONS; do
    printf "Testing $version: "
    container_name="ocrhltest-$version"
    docker run \
    --name "$container_name" \
    -e SOLR_LOG_LEVEL=ERROR \
    -v "$(pwd)/solr/install-plugin.sh:/docker-entrypoint-initdb.d/install-plugin.sh" \
    -v "$(pwd)/solr/core/v8:/opt/core-config" \
    -v "$(pwd)/data:/ocr-data" \
    -v "$plugin_dir:/build" \
    -p "31337:8983" \
    solr:$version \
    solr-precreate ocr /opt/core-config > /dev/null 2>&1 & \
    wait_for_solr "$container_name"
    if ! python3 test.py; then
        printf " !!!FAIL!!!\n"
        docker logs
    else
        printf " OK\n"
    fi
    docker stop "$container_name" > /dev/null
    docker rm "$container_name" > /dev/null
done

# Solr 7 has a different Docker setup
for version in $SOLR7_VERSIONS; do
    printf "Testing $version: "
    docker run \
    --name "ocrhltest-$version" \
    -e SOLR_LOG_LEVEL=ERROR \
    -v "$(pwd)/solr/install-plugin-v7.sh:/docker-entrypoint-initdb.d/install-plugin-v7.sh" \
    -v "$(pwd)/solr/core/v7:/opt/core-config" \
    -v "$(pwd)/data:/ocr-data" \
    -v "$plugin_dir:/build" \
    -p "31337:8983" \
    solr:$version \
    solr-precreate ocr /opt/core-config > /dev/null 2>&1 & \
    wait_for_solr "$container_name"
    if ! python3 test.py; then
        printf " !!!FAIL!!!\n"
        docker logs
    else
        printf " OK\n"
    fi
    docker stop "ocrhltest-$version" > /dev/null
    docker rm "ocrhltest-$version" > /dev/null
done

rm -rf /tmp/solrocr-solr78

echo "INTEGRATION TESTS SUCCEEDED"
rm -rf "$plugin_dir"