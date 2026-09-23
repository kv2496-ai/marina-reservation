# Build stage
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B package -DskipTests

# Run stage
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY --from=build /build/target/marina-reservation.jar app.jar
COPY "Dock Schedule - Synthetic Sample.xlsx" ./
# Persist the JSON flat-file store outside the container layer.
VOLUME ["/app/data"]
ENV MARINA_DATA_DIR=/app/data
ENV MARINA_SOURCE_XLSX="/app/Dock Schedule - Synthetic Sample.xlsx"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
