FROM solr:7.6-alpine

COPY cores/ocr /opt/solr/server/solr/ocr

USER root
RUN chown -R $SOLR_USER:$SOLR_USER /opt/solr/server/solr/ocr

USER solr
RUN mkdir -p /opt/solr/contrib/ocrsearch/lib &&\
    mkdir -p /opt/solr/server/solr/ocr/data
    wget https://github.com/dbmdz/solr-ocrhighlighting/releases/download/0.1/solr-ocrhighlighting-0.1.jar -P/opt/solr/contrib/ocrsearch/lib
RUN echo "ENABLE_REMOTE_JMX_OPTS=true" >> /opt/solr/bin/solr.in.sh &&\
    echo "SOLR_HEAP=4g" >> /opt/solr/bin/solr.in.sh &&\
    find /opt/solr

USER solr
