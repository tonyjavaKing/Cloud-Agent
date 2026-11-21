FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q -DskipTests dependency:go-offline
COPY src ./src
RUN mvn -q -DskipTests package
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/cloud-agent-workbench-1.0.0.jar app.jar
RUN mkdir -p /app/data/sessions
# Keep the real Codex CLI available in the image; demo mode remains the safe default.
RUN apt-get update \
    && apt-get install -y --no-install-recommends nodejs npm \
    && npm install -g @openai/codex@0.171.0 \
    && rm -rf /var/lib/apt/lists/*
ENV PORT=3000
ENV AGENT_MODE=demo
EXPOSE 3000
ENTRYPOINT ["java", "-jar", "app.jar"]
