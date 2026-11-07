# Render has no native Java runtime, so we deploy with Docker.
# Stage 1: build the jar with Maven
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package -DskipTests

# Stage 2: run the jar on a small JRE image
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/app.jar app.jar
ENV SPRING_PROFILES_ACTIVE=prod
EXPOSE 8080
# MaxRAMPercentage keeps Java inside Render's 512 MB free-tier memory
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
