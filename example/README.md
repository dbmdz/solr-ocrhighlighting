# Example Setup

0. Prerequisites:
    - Python 3.8+
    - Docker
    - ~16GiB of free storage
1. Launch containers: `docker compose up -d`
    - Make sure you have write permissions for the subdirectory `./data` since this is where the data will be downloaded to
2. Download and ingest the documents into Solr: `./ingest.py`
    - Please consider additional args in case of problems: `./ingest.py --help`
3. Search the index: `http://localhost:8181`