# Bybit Trade

Aplikacja do zarządzania handlem kryptowalutami na giełdzie Bybit.

## Funkcje

- Zarządzanie pozycjami tradingowymi (otwieranie, zamykanie)
- Integracja z API Bybit V5 (pobieranie otwartych pozycji, sald konta)
- Śledzenie historii transakcji
- Analiza wyników

## Wymagania

- Java 17
- Konto na giełdzie Bybit (oraz klucze API)

## Konfiguracja

### Zmienne środowiskowe

Aplikacja wymaga następujących zmiennych środowiskowych:

```
BYBIT_API_KEY=twój_klucz_api
BYBIT_API_SECRET=twój_sekret_api
BYBIT_TESTNET=true   # true dla środowiska testowego, false dla produkcyjnego
MIN_USDT_AMOUNT_FOR_TRADE=50.0   # minimalna kwota w USDT dla transakcji
RETRACEMENT_DIVIDER=2   # dzielnik dla trailing stop w strategii scalp (domyślnie 2)
```

# Budowanie obrazu Docker

   ```bash
   docker build -t bybit-trade .
   ```


   ```bash
   docker rmi bybit-trade
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