# ---- build ----
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /src
COPY . .
RUN mvn -q -B -DskipTests package

# ---- runtime ----
FROM eclipse-temurin:25-jre
RUN useradd --system --uid 10001 wallet
WORKDIR /app
COPY --from=build /src/wallet-bootstrap/target/wallet-bootstrap-*-SNAPSHOT.jar /app/app.jar
USER 10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
