#During runtime this file will be moved to $PROJECT_HOME/target
FROM public.ecr.aws/amazoncorretto/amazoncorretto:latest
RUN mkdir -p /u01/deploy
WORKDIR /u01/deploy

COPY cdk-fargate-bg-1.0-SNAPSHOT.jar cdk-fargate-bg.jar

ENTRYPOINT [ "sh", "-c", "java -jar /u01/deploy/cdk-fargate-bg.jar"]