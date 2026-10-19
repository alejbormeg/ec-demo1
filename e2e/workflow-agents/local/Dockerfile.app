FROM eclipse-temurin:21-jre
WORKDIR /app
ARG JAR
COPY ${JAR} /app/application.jar
USER 10001:10001
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-jar", "/app/application.jar"]
