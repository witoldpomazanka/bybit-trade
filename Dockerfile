FROM maven:3.8.6-eclipse-temurin-17-alpine AS build
WORKDIR /app

# Kopiujemy pliki konfiguracyjne Mavena
COPY pom.xml .

# Pobieramy zależności, aby wykorzystać cache Dockera
RUN mvn dependency:go-offline -B

# Kopiujemy kod źródłowy
COPY src ./src

# Budujemy aplikację
RUN mvn package -DskipTests

# Etap uruchomieniowy
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# Kopiujemy zbudowaną aplikację z poprzedniego etapu
COPY --from=build /app/target/*.jar app.jar

# Definiujemy zmienne środowiskowe, które mogą być nadpisane przy uruchomieniu
ENV SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/bybit_data
ENV SPRING_DATASOURCE_USERNAME=postgres
ENV SPRING_DATASOURCE_PASSWORD=postgres
ENV BYBIT_API_KEY=test_api_key
ENV BYBIT_API_SECRET=test_secret_key

EXPOSE 8082

ENTRYPOINT ["java", "-jar", "app.jar"] 