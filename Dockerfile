# syntax=docker/dockerfile:1.7
# Build:  docker build --build-context contracts=../festa-infra/contracts -t festa-auth-service .
# (docker compose en festa-infra ya pasa el contexto "contracts").

FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml checkstyle.xml ./
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline
COPY src ./src
COPY --from=contracts . /contracts
# Los tests (incluido el de contrato) corren en el build: si fallan, no hay imagen.
RUN --mount=type=cache,target=/root/.m2 mvn -B -q package -Dfesta.contracts.dir=/contracts

FROM eclipse-temurin:17-jre-alpine
RUN addgroup -S festa && adduser -S festa -G festa
WORKDIR /app
COPY --from=build /build/target/app.jar app.jar
USER festa
EXPOSE 8081
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+UseSerialGC -XX:TieredStopAtLevel=1"
HEALTHCHECK --interval=15s --timeout=5s --start-period=90s --retries=5 \
  CMD wget -qO- http://localhost:8081/health > /dev/null || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]
