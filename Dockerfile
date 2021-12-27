FROM openjdk:8

ENV TZ=Asia/Seoul
ENV LOG4J_FORMAT_MSG_NO_LOOKUPS=true

RUN apt-get update -y
RUN apt-get install rsync -y

WORKDIR /app

RUN mkdir -p /data/indexFile

ARG TARGET
ARG PROJECT_NAME
ARG VERSION

COPY $TARGET/$PROJECT_NAME-$version.jar $PROJECT_NAME-$version.jar

CMD java -jar $PROJECT_NAME-$version.jar

