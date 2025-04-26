#!/bin/bash

# Sprawdzenie, czy baza danych jest uruchomiona
echo "Sprawdzanie, czy baza danych PostgreSQL jest dostępna..."
pg_isready -h localhost -p 5432 > /dev/null 2>&1

if [ $? -ne 0 ]; then
  echo "Baza danych PostgreSQL nie jest dostępna. Uruchamiam kontener postgres..."
  docker-compose up -d postgres
  echo "Czekam 5 sekund na uruchomienie bazy danych..."
  sleep 5
else
  echo "Baza danych PostgreSQL jest już uruchomiona."
fi

# Uruchomienie aplikacji z profilem local
echo "Uruchamiam aplikację z profilem local..."
mvn spring-boot:run -Dspring-boot.run.profiles=local 