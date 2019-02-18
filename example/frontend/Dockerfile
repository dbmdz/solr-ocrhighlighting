FROM node:alpine as builder

COPY . /build

RUN cd /build &&\
    npm install &&\
    npm run build

FROM nginx:alpine

COPY --from=builder /build/build /usr/share/nginx/html
COPY ./vhost.conf /etc/nginx/conf.d/default.conf
