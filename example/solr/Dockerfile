FROM solr:8.1-slim

USER root
RUN mkdir -p /opt/solr/contrib/ocrsearch/lib &&\
    wget https://github.com/dbmdz/solr-ocrhighlighting/releases/download/0.3.1/solr-ocrhighlighting-0.3.1.jar -P/opt/solr/contrib/ocrsearch/lib

USER solr
