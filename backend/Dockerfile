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
# Tuned for Render's free tier (0.1 CPU, 512 MB): SerialGC = no extra GC threads, TieredStopAtLevel=1 = quick
# JIT only (faster cold start, less CPU), Xss512k = smaller thread stacks, MaxRAMPercentage keeps Java inside 512 MB
ENTRYPOINT ["java", "-XX:+UseSerialGC", "-XX:TieredStopAtLevel=1", "-Xss512k", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
