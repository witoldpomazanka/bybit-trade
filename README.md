# Bybit Trade

Serwis do obsługi tradingu kryptowalutami na platformie Bybit.

## Funkcjonalności

- Otwieranie pozycji tradingowych
- Zamykanie pozycji tradingowych
- Pobieranie listy wszystkich pozycji
- Pobieranie listy otwartych pozycji

## Wymagania

- Java 17
- Maven
- Docker i Docker Compose
- PostgreSQL (opcjonalnie lokalnie, domyślnie używane z Docker Compose)

## Konfiguracja

Serwis korzysta z danych API Bybit pobieranych ze zmiennych środowiskowych:
- `BYBIT_API_KEY` - klucz API Bybit
- `BYBIT_API_SECRET` - sekretny klucz API Bybit

## Uruchomienie lokalnie

1. Sklonuj repozytorium
2. Utwórz plik `.env` na podstawie przykładu poniżej:
```
# Zmienne dla PostgreSQL
POSTGRES_USER=postgres
POSTGRES_PASSWORD=postgres
POSTGRES_DB=bybit_data

# Zmienne dla Bybit API
BYBIT_API_KEY=your_bybit_api_key
BYBIT_API_SECRET=your_bybit_api_secret

# Pozostałe zmienne dla usług
PGADMIN_DEFAULT_EMAIL=admin@example.com
PGADMIN_DEFAULT_PASSWORD=admin
N8N_BASIC_AUTH_USER=admin
N8N_BASIC_AUTH_PASSWORD=admin
TELEGRAM_API_ID=your_api_id
TELEGRAM_API_HASH=your_api_hash
TELEGRAM_PHONE=your_phone
```
3. Upewnij się, że masz lokalnie uruchomioną bazę PostgreSQL lub uruchom ją z Docker Compose:
```
docker-compose up -d postgres
```
4. Zbuduj projekt komendą:
```
mvn clean install
```
5. Uruchom aplikację z profilem `local`:
```
java -jar -Dspring.profiles.active=local target/trade-0.0.1-SNAPSHOT.jar
```

Alternatywnie, możesz uruchomić z Maven:
```
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

## Uruchomienie z Docker Compose

1. Utwórz plik `.env` jak opisano powyżej
2. Uruchom wszystkie usługi:
```
docker-compose up -d
```

Dostępne usługi:
- **bybit-trade**: `http://localhost:8082` - serwis do handlu na Bybit (w Docker Compose)
- **bybit-trade**: `http://localhost:8888` - serwis do handlu na Bybit (przy lokalnym uruchomieniu)
- **PostgreSQL**: `localhost:5432` - baza danych
- **pgAdmin**: `http://localhost:8081` - administracja bazą danych
- **n8n**: `http://localhost:5678` - platforma automatyzacji
- **telethon-listener**: `http://localhost:8080` - serwis do komunikacji z Telegramem

## Integracja z istniejącymi usługami

Serwis bybit-trade został zintegrowany z:
- **PostgreSQL** - współdzieli tę samą bazę danych co inne usługi
- **n8n** - można skonfigurować przepływy pracy, które będą wywoływać API serwisu bybit-trade

## Endpointy API

### Pozycje

- `POST /api/positions` - Otwórz nową pozycję
- `POST /api/positions/{id}/close` - Zamknij pozycję
- `GET /api/positions` - Pobierz wszystkie pozycje
- `GET /api/positions/open` - Pobierz otwarte pozycje

## Przykład wywołania API

Otwieranie pozycji:
```bash
curl -X POST http://localhost:8888/api/positions \
  -H "Content-Type: application/json" \
  -d '{
    "symbol": "BTCUSDT",
    "type": "LONG",
    "amount": 0.001,
    "takeProfitPrice": 37000,
    "stopLossPrice": 35000
  }'
```

Zamykanie pozycji:
```bash
curl -X POST http://localhost:8888/api/positions/1/close
```

Pobieranie wszystkich pozycji:
```bash
curl -X GET http://localhost:8888/api/positions
```

Pobieranie otwartych pozycji:
```bash
curl -X GET http://localhost:8888/api/positions/open
```