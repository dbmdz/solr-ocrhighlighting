#!/bin/bash
set -e
SOLR_HOST="${SOLR_HOST:-localhost}"
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null 2>&1 && pwd )"
SOLR7_VERSIONS="7.7 7.6 7.5"
SOLR8_VERSIONS="8.11 8.10 8.9 8.8 8.7 8.6 8.5 8.4 8.3 8.2 8.1 8.0"
SOLR9_VERSIONS="9.9 9.8 9.7 9.6 9.5 9.4 9.3 9.2 9.1 9.0"
SOLR10_VERSIONS="10.0"

# Prefer Docker when available, but allow Podman so the same test script works in both setups.
if command -v docker >/dev/null 2>&1; then
    CONTAINER_RUNTIME="docker"
elif command -v podman >/dev/null 2>&1; then
    CONTAINER_RUNTIME="podman"
else
    echo "Neither docker nor podman is available in PATH."
    exit 1
fi

# Keep per-version failures easy to scan when the full test matrix is running.
log_version_error() {
    echo "ERROR [$1]: $2" >&2
}

# Best-effort cleanup for interrupt handling, where the container may already be half torn down.
cleanup_container_quietly() {
    local container_name="$1"

    "$CONTAINER_RUNTIME" stop "$container_name" > /dev/null 2>&1 || true
    "$CONTAINER_RUNTIME" rm "$container_name" > /dev/null 2>&1 || true
}

cleanup_container() {
    local version="$1"
    local container_name="$2"

    if ! "$CONTAINER_RUNTIME" stop "$container_name" > /dev/null; then
        log_version_error "$version" "failed to stop container $container_name"
        return 1
    fi
    if ! "$CONTAINER_RUNTIME" rm "$container_name" > /dev/null; then
        log_version_error "$version" "failed to remove container $container_name"
        return 1
    fi
}

# Remove the active container and temporary plugin directory so Ctrl+C does not leave state behind.
on_interrupt() {
    trap - INT TERM
    echo >&2
    echo "Interrupted." >&2
    if [[ -n "${current_container_name:-}" ]]; then
        cleanup_container_quietly "$current_container_name"
    fi
    if [[ -n "${plugin_dir:-}" && -d "${plugin_dir:-}" ]]; then
        rm -rf "$plugin_dir"
    fi
    exit 130
}

trap on_interrupt INT TERM

wait_for_solr() {
    local version="$1"
    local container_name="$2"
    status="404"
    while [[ "$status" != "200" ]]; do
        set +e
        status="$(curl -s -o /dev/null http://$SOLR_HOST:31337/solr/ocr/select -w '%{http_code}')"
        # Poll the container state as well so startup failures do not loop forever waiting on HTTP.
        inspect_status=$("$CONTAINER_RUNTIME" inspect -f '{{.State.Status}}' "$container_name" 2>/dev/null)
        if [[ "$status" == "500" ]]; then
            log_version_error "$version" "Solr returned HTTP 500"
            "$CONTAINER_RUNTIME" logs "$container_name"
            set -e
            return 1
        fi
        if [[ "$inspect_status" != "running" && "$inspect_status" != "created" ]]; then
            log_version_error "$version" "container is not running while waiting for Solr (state: ${inspect_status:-missing})"
            "$CONTAINER_RUNTIME" logs "$container_name"
            set -e
            return 1
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

solr910_jar="$(ls ../target/*.jar |egrep -v '(javadoc|original|source|solr78)')"
solr78_jar="$(ls ../target/*.jar |egrep 'solr78.jar')"
if [ -z "$solr78_jar" ]; then
    echo "No solr78 jar found in ../target, please run 'util/patch_solr78_bytecode.py' in the parent directory first!"
    exit 1
fi

failed_versions=0
current_container_name=""

# Solr 10 loads the plugin directly from the core lib directory instead of the older install script.
mkdir -p "./solr/core/v10/lib"
cp $solr910_jar "./solr/core/v10/lib/"
for version in ${SOLR10_VERSIONS}; do
    printf "Testing $version: "
    container_name="ocrhltest-$version"
    current_container_name="$container_name"
    if ! "$CONTAINER_RUNTIME" run -d \
    --name "$container_name" \
    -e SOLR_LOG_LEVEL=ERROR \
    -v "$(pwd)/solr/core/v10:/opt/core-config" \
    -v "$(pwd)/data:/ocr-data" \
    -p "31337:8983" \
    docker.io/solr:$version \
    solr-precreate ocr /opt/core-config > /dev/null; then
        printf " !!!FAIL!!!\n"
        log_version_error "$version" "failed to start container with $CONTAINER_RUNTIME"
        failed_versions=1
        current_container_name=""
        continue
    fi
    if ! wait_for_solr "$version" "$container_name"; then
        printf " !!!FAIL!!!\n"
        cleanup_container "$version" "$container_name" || true
        failed_versions=1
        current_container_name=""
        continue
    fi
    if ! python3 test.py; then
        printf " !!!FAIL!!!\n"
        log_version_error "$version" "integration test command failed"
        "$CONTAINER_RUNTIME" logs "$container_name"
        failed_versions=1
    else
        printf " OK\n"
    fi
    cleanup_container "$version" "$container_name" || failed_versions=1
    current_container_name=""
done

plugin_dir="$(mktemp -d)"
# Solr 9 still expects the plugin JAR to be mounted into a writable build directory.
cp $solr910_jar "$plugin_dir"
chmod -R a+rwx "$plugin_dir"
for version in ${SOLR9_VERSIONS}; do
    printf "Testing $version: "
    container_name="ocrhltest-$version"
    current_container_name="$container_name"
    if ! "$CONTAINER_RUNTIME" run -d \
    --name "$container_name" \
    -e SOLR_LOG_LEVEL=ERROR \
    -e SOLR_SECURITY_MANAGER_ENABLED=false \
    -e SOLR_OPTS="-Dsolr.config.lib.enabled=true" \
    -v "$(pwd)/solr/install-plugin-v8_v9.sh:/docker-entrypoint-initdb.d/install-plugin.sh" \
    -v "$(pwd)/solr/core/v9:/opt/core-config" \
    -v "$(pwd)/data:/ocr-data" \
    -v "$plugin_dir:/build" \
    -p "31337:8983" \
    docker.io/solr:$version \
    solr-precreate ocr /opt/core-config > /dev/null; then
        printf " !!!FAIL!!!\n"
        log_version_error "$version" "failed to start container with $CONTAINER_RUNTIME"
        failed_versions=1
        current_container_name=""
        continue
    fi
    if ! wait_for_solr "$version" "$container_name"; then
        printf " !!!FAIL!!!\n"
        cleanup_container "$version" "$container_name" || true
        failed_versions=1
        current_container_name=""
        continue
    fi
    if ! python3 test.py; then
        printf " !!!FAIL!!!\n"
        log_version_error "$version" "integration test command failed"
        "$CONTAINER_RUNTIME" logs "$container_name"
        failed_versions=1
    else
        printf " OK\n"
    fi
    cleanup_container "$version" "$container_name" || failed_versions=1
    current_container_name=""
done
rm -rf "$plugin_dir"/*.jar

# Swap in the Solr 7/8-compatible artifact before continuing with the older image matrix.
cp $solr78_jar "$plugin_dir"
# Solr 8 versions, use a different plugin JAR
for version in $SOLR8_VERSIONS; do
    printf "Testing $version: "
    container_name="ocrhltest-$version"
    current_container_name="$container_name"
    if ! "$CONTAINER_RUNTIME" run -d \
    --name "$container_name" \
    -e SOLR_LOG_LEVEL=ERROR \
    -v "$(pwd)/solr/install-plugin-v8_v9.sh:/docker-entrypoint-initdb.d/install-plugin.sh" \
    -v "$(pwd)/solr/core/v8:/opt/core-config" \
    -v "$(pwd)/data:/ocr-data" \
    -v "$plugin_dir:/build" \
    -p "31337:8983" \
    docker.io/solr:$version \
    solr-precreate ocr /opt/core-config > /dev/null; then
        printf " !!!FAIL!!!\n"
        log_version_error "$version" "failed to start container with $CONTAINER_RUNTIME"
        failed_versions=1
        current_container_name=""
        continue
    fi
    if ! wait_for_solr "$version" "$container_name"; then
        printf " !!!FAIL!!!\n"
        cleanup_container "$version" "$container_name" || true
        failed_versions=1
        current_container_name=""
        continue
    fi
    if ! python3 test.py; then
        printf " !!!FAIL!!!\n"
        log_version_error "$version" "integration test command failed"
        "$CONTAINER_RUNTIME" logs "$container_name"
        failed_versions=1
    else
        printf " OK\n"
    fi
    cleanup_container "$version" "$container_name" || failed_versions=1
    current_container_name=""
done

# Solr 7 has a different Docker setup
for version in $SOLR7_VERSIONS; do
    printf "Testing $version: "
    container_name="ocrhltest-$version"
    current_container_name="$container_name"
    if ! "$CONTAINER_RUNTIME" run -d \
    --name "ocrhltest-$version" \
    -e SOLR_LOG_LEVEL=ERROR \
    -v "$(pwd)/solr/install-plugin-v7.sh:/docker-entrypoint-initdb.d/install-plugin-v7.sh" \
    -v "$(pwd)/solr/core/v7:/opt/core-config" \
    -v "$(pwd)/data:/ocr-data" \
    -v "$plugin_dir:/build" \
    -p "31337:8983" \
    docker.io/solr:$version \
    solr-precreate ocr /opt/core-config > /dev/null; then
        printf " !!!FAIL!!!\n"
        log_version_error "$version" "failed to start container with $CONTAINER_RUNTIME"
        failed_versions=1
        current_container_name=""
        continue
    fi
    if ! wait_for_solr "$version" "$container_name"; then
        printf " !!!FAIL!!!\n"
        cleanup_container "$version" "$container_name" || true
        failed_versions=1
        current_container_name=""
        continue
    fi
    if ! python3 test.py; then
        printf " !!!FAIL!!!\n"
        log_version_error "$version" "integration test command failed"
        "$CONTAINER_RUNTIME" logs "$container_name"
        failed_versions=1
    else
        printf " OK\n"
    fi
    cleanup_container "$version" "$container_name" || failed_versions=1
    current_container_name=""
done

rm -rf /tmp/solrocr-solr78
rm -rf "$plugin_dir"
current_container_name=""

if [[ "$failed_versions" -ne 0 ]]; then
    echo "INTEGRATION TESTS FAILED"
    exit 1
fi

echo "INTEGRATION TESTS SUCCEEDED"
