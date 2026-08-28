# syntax=docker/dockerfile:1
#
# Built from the REPO ROOT as context, not this directory: a module needs the aggregator
# pom and notification-common to build at all.
#   docker build -f notification-api/Dockerfile .

# ---- build stage -------------------------------------------------------------
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /build

# Poms first, so the dependency layer is cached unless a pom changes. ALL of them: the
# aggregator lists three modules and refuses to load if any of their poms is missing,
# even when only one module is being built.
COPY pom.xml .
COPY notification-common/pom.xml notification-common/
COPY notification-api/pom.xml notification-api/
COPY notification-worker/pom.xml notification-worker/
RUN mvn -B -q -pl notification-api -am dependency:go-offline

# Then the sources. Tests are skipped here: they need Docker (Testcontainers) and are the
# CI job's responsibility, not the image build's.
COPY notification-common/src notification-common/src
COPY notification-api/src notification-api/src
RUN mvn -B -q -pl notification-api -am clean package -DskipTests

# ---- runtime stage -----------------------------------------------------------
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

# Run as a non-root user rather than the image default.
RUN groupadd --system app && useradd --system --gid app app
USER app

COPY --from=build /build/notification-api/target/notification-api-*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
