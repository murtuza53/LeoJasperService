# Build stage
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY pom.xml .
COPY leo-jasper-core/pom.xml leo-jasper-core/
COPY leo-jasper-rest/pom.xml leo-jasper-rest/
COPY leo-jasper-cli/pom.xml leo-jasper-cli/
RUN mvn -B -ntp -q -pl leo-jasper-core,leo-jasper-rest,leo-jasper-cli -am dependency:go-offline || true
COPY leo-jasper-core/ leo-jasper-core/
COPY leo-jasper-rest/ leo-jasper-rest/
COPY leo-jasper-cli/ leo-jasper-cli/
RUN mvn -B -ntp -DskipTests package

# Runtime stage
FROM eclipse-temurin:17-jre
WORKDIR /app
RUN useradd --system --no-create-home --shell /usr/sbin/nologin leo \
    && mkdir -p /app/templates && chown -R leo:leo /app
COPY --from=build /src/leo-jasper-rest/target/leo-jasper-rest.jar /app/app.jar
USER leo
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
    CMD wget --quiet --tries=1 --spider http://localhost:8080/actuator/health || exit 1
ENV JAVA_OPTS=""
ENTRYPOINT ["sh","-c","exec java $JAVA_OPTS -jar /app/app.jar"]
