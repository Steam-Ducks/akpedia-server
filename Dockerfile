FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app

COPY pom.xml .
RUN mvn -B dependency:go-offline

COPY src ./src
RUN mvn -B clean package -DskipTests

FROM eclipse-temurin:17-jre AS runtime
WORKDIR /app

# /app/data holds the account deletion log; a new named volume mounted there takes its owner.
RUN groupadd --system spring && useradd --system --gid spring spring \
    && mkdir -p /app/data && chown spring:spring /app/data
USER spring:spring

COPY --from=build /app/target/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
