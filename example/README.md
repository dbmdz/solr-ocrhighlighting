# Example Setup

0. Prerequisites:
    - Python 3
    - Docker and `docker-compose`
    - ~16GiB of free storage
2. Launch the containers: `docker-compose up -d`
3. Download and ingest the documents into Solr: `./ingest.py`
4. Search the corpus: `http://localhost:8181`