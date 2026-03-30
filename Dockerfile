# Build Stage
FROM maven:3.9.6-eclipse-temurin-17 AS build
WORKDIR /app
# Copy the POM and download dependencies first (for faster caching)
COPY pom.xml .
RUN mvn dependency:go-offline -B
# Copy the source code and build
COPY src ./src
RUN mvn clean package -DskipTests

# Run Stage
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
# Copy the packaged jar from the build stage
COPY --from=build /app/target/URL-Shortener-0.0.1-SNAPSHOT.jar app.jar
# Expose the default Spring Boot port
EXPOSE 8080
# Run the application
ENTRYPOINT ["java", "-jar", "app.jar"]
