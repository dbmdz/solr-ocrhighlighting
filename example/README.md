# Google Books hOCR Example Setup

0. Prerequisites:
    - Python 3 with the `click`, `requests`, `pillow` (or `PIL`) and `lxml` libraries installed
    - Docker and `docker-compose`
    - more than 250GiB of available storage
1. Download and extract the dataset **(>250GiB!!!)**: `./download_google1000.py`
2. Launch the containers: `docker-compose up -d`
3. Ingest the volumes into Solr: `./ingest_google1000.py`
4. Search the corpus: `http://localhost:8181`