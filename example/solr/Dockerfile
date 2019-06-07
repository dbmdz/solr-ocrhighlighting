FROM solr:7.6-alpine

COPY ocrtest /opt/solr/server/solr/ocrtest

USER root
RUN chown -R $SOLR_USER:$SOLR_USER /opt/solr/server/solr/ocrtest

USER solr
RUN mkdir -p /opt/solr/server/solr/ocrtest/lib &&\
    mkdir -p /opt/solr/server/solr/ocrtest/data &&\
    wget https://github.com/dbmdz/solr-ocrhighlighting/releases/download/0.1/solr-ocrhighlighting-0.1.jar -P/opt/solr/server/solr/ocrtest/lib/
RUN echo "ENABLE_REMOTE_JMX_OPTS=true" >> /opt/solr/bin/solr.in.sh &&\
    echo "SOLR_HEAP=4g" >> /opt/solr/bin/solr.in.sh &&\
    find /opt/solr/server/solr/ocrtest

USER solr
