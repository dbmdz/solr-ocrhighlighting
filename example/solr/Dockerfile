FROM solr:7.6-alpine

COPY ocrtest /opt/solr/server/solr/ocrtest

USER root
RUN chown -R $SOLR_USER:$SOLR_USER /opt/solr/server/solr/ocrtest

USER solr
#RUN mkdir -p /opt/solr/server/solr/ocrtest/lib &&\
#    wget https://github.com/dbmdz/solr-ocrpayload-plugin/releases/download/0.2/solr-ocrpayload-plugin-0.2.jar -P/opt/solr/server/solr/ocrtest/lib/ &&\
RUN echo "ENABLE_REMOTE_JMX_OPTS=true" >> /opt/solr/bin/solr.in.sh &&\
    echo "SOLR_HEAP=4g" >> /opt/solr/bin/solr.in.sh
RUN bin/solr start -v &&\
    bin/solr create_core -c ocrtest &&\
    bin/solr stop

USER solr
