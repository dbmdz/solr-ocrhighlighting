FROM node:alpine as builder

COPY . /build

RUN apk add git &&\
    cd /build &&\
    npm install &&\
    npm run build && \
    git clone --single-branch --branch search-from-url https://github.com/jbaiter/universalviewer /tmp/uv &&\
    cd /tmp/uv && \
    npm install &&\
    ./node_modules/grunt/bin/grunt build --dist

FROM nginx:alpine

COPY --from=builder /build/build /usr/share/nginx/html
COPY --from=builder /tmp/uv/dist /usr/share/nginx/html/uv
COPY ./vhost.conf /etc/nginx/conf.d/default.conf

