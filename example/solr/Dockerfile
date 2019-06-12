FROM solr:7.6-alpine

COPY cores/google1000 /opt/solr/server/solr/google1000
COPY cores/bnl_lunion /opt/solr/server/solr/bnl_lunion

USER root
RUN chown -R $SOLR_USER:$SOLR_USER /opt/solr/server/solr/google1000 &&\
    chown -R $SOLR_USER:$SOLR_USER /opt/solr/server/solr/bnl_lunion

USER solr
RUN mkdir -p /opt/solr/contrib/ocrsearch/lib &&\
    mkdir -p /opt/solr/server/solr/google1000/data &&\
    mkdir -p /opt/solr/server/solr/bnl_lunion/data &&\
    wget https://github.com/dbmdz/solr-ocrhighlighting/releases/download/0.1/solr-ocrhighlighting-0.1.jar -P/opt/solr/contrib/ocrsearch/lib
RUN echo "ENABLE_REMOTE_JMX_OPTS=true" >> /opt/solr/bin/solr.in.sh &&\
    echo "SOLR_HEAP=4g" >> /opt/solr/bin/solr.in.sh &&\
    find /opt/solr

USER solr
