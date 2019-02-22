FROM node:alpine as builder

COPY . /build

RUN apk add git &&\
    cd /build &&\
    npm install &&\
    npm run build

FROM nginx:alpine

COPY --from=builder /build/build /usr/share/nginx/html
COPY --from=builder /build/node_modules/universalviewer/dist /usr/share/nginx/html/uv
COPY ./vhost.conf /etc/nginx/conf.d/default.conf

