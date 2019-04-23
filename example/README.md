# Google Books hOCR Example Setup

0. Prerequisites:
    - Python 3
    - Docker and `docker-compose`
    - 8GiB of free storage
2. Launch the containers: `docker-compose up -d`
3. Download and ingest the volumes into Solr: `./ingest_google1000.py`
4. Search the corpus: `http://localhost:8181`