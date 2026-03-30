FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn -q -DskipTests clean package

FROM eclipse-temurin:21-jre-jammy
RUN useradd --create-home --uid 10001 --shell /usr/sbin/nologin mcp
WORKDIR /home/mcp
COPY --from=build --chown=mcp:mcp /app/dist/jenkins-analyzer-mcp.jar ./jenkins-analyzer-mcp.jar
USER mcp
ENV HOME=/home/mcp
# MCP stdio: process must read stdin / write stdout; no HTTP port exposed.
ENTRYPOINT ["java", "-jar", "/home/mcp/jenkins-analyzer-mcp.jar"]
