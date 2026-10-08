# app-api only: the desktop is not a container, it runs on the customer's machine.
# ---- build ----
FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
# Wrapper and build scripts first: the dependency layer stays cached until they change.
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
COPY app-contract/build.gradle.kts app-contract/
COPY app-api/build.gradle.kts app-api/
COPY app-desktop/build.gradle.kts app-desktop/
RUN ./gradlew --no-daemon -q :app-api:dependencies > /dev/null
COPY app-contract/src app-contract/src
COPY app-api/src app-api/src
RUN ./gradlew --no-daemon -q :app-api:bootJar

# ---- runtime ----
FROM eclipse-temurin:25-jre
WORKDIR /app

# Same OpenTelemetry Java agent version as the other services; enabled via JAVA_TOOL_OPTIONS in compose.
ADD https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.31.0/opentelemetry-javaagent.jar /app/opentelemetry-javaagent.jar
COPY --from=build /src/app-api/build/libs/app-api-*-SNAPSHOT.jar /app/app.jar

RUN useradd --system --uid 10001 appapi && chown -R appapi:appapi /app
USER 10001

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
