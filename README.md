# Bybit Trade

Aplikacja do zarządzania handlem kryptowalutami na giełdzie Bybit.

## Funkcje

- Zarządzanie pozycjami tradingowymi (otwieranie, zamykanie)
- Integracja z API Bybit V5 (pobieranie otwartych pozycji, sald konta)
- Śledzenie historii transakcji
- Analiza wyników

## Wymagania

- Java 17
- PostgreSQL
- Konto na giełdzie Bybit (oraz klucze API)

## Konfiguracja

### Zmienne środowiskowe

Aplikacja wymaga następujących zmiennych środowiskowych:

```
POSTGRES_HOST=localhost
POSTGRES_PORT=5432
POSTGRES_DB=bybit_trade
POSTGRES_USER=user
POSTGRES_PASSWORD=password
BYBIT_API_KEY=twój_klucz_api
BYBIT_API_SECRET=twój_sekret_api
BYBIT_TESTNET=true   # true dla środowiska testowego, false dla produkcyjnego
```

### Uruchomienie lokalne

1. Sklonuj repozytorium
2. Skonfiguruj zmienne środowiskowe
3. Uruchom PostgreSQL
4. Uruchom aplikację za pomocą skryptu:

```
./run-local.sh
```

Lub na Windows:

```
run-local.bat
```

### Uruchomienie w Dockerze

```
docker-compose up -d
```

## Korzystanie z API

Aplikacja udostępnia REST API. Przykładowe endpointy:

### Zarządzanie pozycjami

#### Pobieranie wszystkich pozycji

```bash
curl -X GET http://localhost:8888/api/positions
```

#### Pobieranie otwartych pozycji

```bash
curl -X GET http://localhost:8888/api/positions/open
```

#### Otwieranie pozycji

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

#### Zamykanie pozycji

```bash
curl -X POST http://localhost:8888/api/positions/1/close
```

### Integracja z Bybit API

#### Pobieranie otwartych pozycji z Bybit

```bash
curl -X GET http://localhost:8888/api/bybit/positions/open
```

#### Pobieranie salda konta z Bybit

```bash
curl -X GET http://localhost:8888/api/bybit/account/balance
```

## Kolekcja Postman

W repozytorium znajduje się kolekcja Postman (`postman_collection.json`), która ułatwia testowanie API.