FROM clojure:openjdk-11-lein-2.9.1 as builder

RUN mkdir -p /app
WORKDIR /app

COPY project.clj ./project.clj

RUN lein with-profile uberjar deps

COPY resources ./resources
COPY src ./src

RUN env LEIN_SNAPSHOTS_IN_RELEASE=1 \
    lein uberjar && \
  cp ./target/iny.jar ./iny.jar

FROM oracle/graalvm-ce:latest as compiler

RUN mkdir -p /app
WORKDIR /app

RUN gu install native-image

COPY --from=builder /app/iny.jar .

COPY resources/reflect-config.json ./reflect.json
COPY native-compile ./native-compile

RUN /app/native-compile

FROM debian:buster-slim

RUN mkdir -p /opt
WORKDIR /opt

COPY --from=compiler /app/iny /opt/iny

CMD /opt/iny
