#During runtime this file will be moved to $PROJECT_HOME/target
FROM public.ecr.aws/amazoncorretto/amazoncorretto:20-al2-jdk
RUN mkdir -p /u01/deploy
WORKDIR /u01/deploy

COPY example_component-1.0-SNAPSHOT.jar example_component.jar
USER aws
HEALTHCHECK CMD curl --fail http://localhost:8080 || exit 1
ENTRYPOINT [ "sh", "-c", "java -jar /u01/deploy/example_component.jar"]