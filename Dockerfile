FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/target/exe-be-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 10000
CMD ["sh", "-c", "java -jar app.jar --server.port=${PORT:-10000}"]
