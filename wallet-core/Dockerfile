# ---- build ----
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /src
COPY . .
RUN mvn -q -B -DskipTests package

# ---- runtime ----
FROM eclipse-temurin:25-jre
WORKDIR /app

# 1. Baixa o OpenTelemetry Java Agent ANTES de mudar de usuário (como root)
ADD https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.31.0/opentelemetry-javaagent.jar /app/opentelemetry-javaagent.jar

# 2. Copia o jar da aplicação
COPY --from=build /src/wallet-bootstrap/target/wallet-bootstrap-*-SNAPSHOT.jar /app/app.jar

# 3. Cria o usuário e ajusta permissões
RUN useradd --system --uid 10001 wallet && \
    chown -R wallet:wallet /app

# 4. Muda para o usuário não-privilegiado
USER 10001
EXPOSE 8080

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]