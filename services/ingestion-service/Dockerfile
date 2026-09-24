# Build context is the repository root:  docker build -f services/ingestion-service/Dockerfile .
ARG MODULE_PATH=services/ingestion-service

FROM maven:3.9-eclipse-temurin-25 AS build
ARG MODULE_PATH
WORKDIR /workspace
COPY pom.xml mvnw ./
COPY .mvn .mvn
COPY platform platform
COPY infrastructure infrastructure
COPY services services
# Cache mount: without it each of the eleven service images re-downloads the whole
# dependency tree independently. That is slow, and the resulting burst of parallel
# downloads is what exhausted Docker's DNS resolver and failed the first full build
# with "Temporary failure in name resolution". The cache is shared across images.
RUN --mount=type=cache,target=/root/.m2/repository,sharing=locked mvn -q -pl ${MODULE_PATH} -am -DskipTests package

FROM eclipse-temurin:25-jre
ARG MODULE_PATH
# curl for the Compose healthcheck against /actuator/health/readiness.
# tesseract-ocr + libtesseract-dev: Tess4J loads libtesseract from the system on
# Linux; the bundled eng.traineddata still comes from the jar.
RUN apt-get update && apt-get install -y --no-install-recommends curl tesseract-ocr libtesseract-dev \
    && rm -rf /var/lib/apt/lists/*
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
WORKDIR /app
COPY --from=build /workspace/${MODULE_PATH}/target/*.jar app.jar
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
