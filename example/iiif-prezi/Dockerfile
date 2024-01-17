FROM python:3.10

WORKDIR /usr/src/app

COPY . .

RUN pip install pipenv
RUN pipenv install

CMD [ "pipenv", "run", "prod"]
