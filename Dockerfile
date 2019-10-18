FROM openjdk:11-jdk-slim

WORKDIR /opt/app/

COPY ./target/trusted-advisor-monitor-*-jar-with-dependencies.jar /opt/app/jar.jar

ENTRYPOINT exec java $EXTRA_JAVA_OPTS -jar jar.jar