FROM openjdk:8

ENV TZ=Asia/Seoul
ENV LOG4J_FORMAT_MSG_NO_LOOKUPS=true

RUN apt-get update -y
RUN apt-get install rsync -y

WORKDIR /app

RUN mkdir -p /data/indexFile

ARG PROJECT_NAME
ARG VERSION

COPY target/${PROJECT_NAME}-${VERSION}.jar ${PROJECT_NAME}-${VERSION}.jar

CMD java -jar ${PROJECT_NAME}-${VERSION}.jar
