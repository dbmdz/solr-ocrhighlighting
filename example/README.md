# Example Setup

0. Prerequisites:
    - Python 3.8+
    - docker compose V2
    - ~16GiB of free storage
1. Clear local data dir if exists: `rm -vr data`
2. Launch containers: `docker compose up -d`
    - Check permissions for subdirectory `./data` since there goes the load
3. Download and ingest the documents into Solr: `./ingest.py`
    - If only interested in books: `./ingest_books_only.py`
4. Search the index: `http://localhost:8181`