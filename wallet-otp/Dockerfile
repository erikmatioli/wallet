# ---- build ----
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /src
# POM first: the dependency layer (Kotlin compiler included) stays cached until pom.xml changes.
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B -DskipTests package

# ---- runtime ----
FROM eclipse-temurin:25-jre
WORKDIR /app

# Same OpenTelemetry Java agent version as wallet-core and wallet-pix; enabled via JAVA_TOOL_OPTIONS in compose.
ADD https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.31.0/opentelemetry-javaagent.jar /app/opentelemetry-javaagent.jar
COPY --from=build /src/target/wallet-otp-*-SNAPSHOT.jar /app/app.jar

RUN useradd --system --uid 10001 otp && chown -R otp:otp /app
USER 10001

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
