FROM amazoncorretto:8
RUN mkdir -p /u01/deploy
WORKDIR /u01/deploy

COPY target/cdk-fargate-bg-1.0-SNAPSHOT.jar cdk-fargate-bg.jar

ENTRYPOINT [ "sh", "-c", "java -jar /u01/deploy/cdk-fargate-bg.jar"]