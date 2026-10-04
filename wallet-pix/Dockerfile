# One image definition for both runnable modules: --build-arg MODULE=pix-service | spi-simulator
# ---- build ----
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /src
COPY . .
ARG MODULE=pix-service
RUN mvn -q -B -DskipTests -pl ${MODULE} -am package

# ---- runtime ----
FROM eclipse-temurin:25-jre
WORKDIR /app
ARG MODULE=pix-service

# Same OpenTelemetry Java agent version as wallet-core; enabled via JAVA_TOOL_OPTIONS in compose.
ADD https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.31.0/opentelemetry-javaagent.jar /app/opentelemetry-javaagent.jar
COPY --from=build /src/${MODULE}/target/${MODULE}-*-SNAPSHOT.jar /app/app.jar

RUN useradd --system --uid 10001 pix && chown -R pix:pix /app
USER 10001

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
