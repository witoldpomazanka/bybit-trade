@echo off
echo Sprawdzanie, czy baza danych PostgreSQL jest dostepna...

:: Próba połączenia z PostgreSQL (wymaga zainstalowanego PostgreSQL client na Windows lub dostosowania)
docker-compose ps postgres | findstr "Up" > nul
if %errorlevel% neq 0 (
    echo Baza danych PostgreSQL nie jest dostepna. Uruchamiam kontener postgres...
    docker-compose up -d postgres
    echo Czekam 5 sekund na uruchomienie bazy danych...
    timeout /t 5 /nobreak > nul
) else (
    echo Baza danych PostgreSQL jest juz uruchomiona.
)

:: Uruchomienie aplikacji z profilem local
echo Uruchamiam aplikacje z profilem local...
mvn spring-boot:run -Dspring-boot.run.profiles=local 