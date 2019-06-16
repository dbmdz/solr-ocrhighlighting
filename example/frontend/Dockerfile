FROM node:alpine as builder

COPY . /build

RUN apk add git &&\
    cd /build &&\
    npm install &&\
    npm run build

FROM nginx:alpine

COPY --from=builder /build/build /usr/share/nginx/html
RUN mkdir /usr/share/nginx/html/viewer
COPY ./vhost.conf /etc/nginx/conf.d/default.conf
COPY ./viewer.html /usr/share/nginx/html/viewer/index.html

