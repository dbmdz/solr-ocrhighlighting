FROM python:3.7

WORKDIR /usr/src/app

COPY . .

RUN pip install pipenv
RUN pipenv install

CMD [ "pipenv", "run", "main.py"]
