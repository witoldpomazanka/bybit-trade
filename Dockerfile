FROM maven:3.8.6-eclipse-temurin-17 AS build
WORKDIR /app

# Kopiujemy tylko pom.xml i wykonujemy pobranie zależności
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn dependency:go-offline -B

# Kopiujemy kod źródłowy i budujemy aplikację
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn package -DskipTests -Dmaven.test.skip=true

# Etap uruchomieniowy - używamy mniejszego obrazu bazowego
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

# Kopiujemy tylko jar
COPY --from=build /app/target/*.jar app.jar

# Definiujemy tylko domyślne wartości dla niekrytycznych zmiennych
ENV SPRING_PROFILE=default

EXPOSE 8082

# Optymalizacje JVM dla kontenerów
ENTRYPOINT ["java", \
    "-XX:+UseContainerSupport", \
    "-XX:MaxRAMPercentage=75.0", \
    "-jar", "app.jar"] 