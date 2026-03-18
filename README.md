# BloFin Trade

Aplikacja do zarządzania handlem kryptowalutami na giełdzie BloFin.

## Funkcje

- Zarządzanie pozycjami tradingowymi (otwieranie, zamykanie)
- Integracja z API BloFin (pobieranie otwartych pozycji, sald konta)
- Śledzenie historii transakcji
- Analiza wyników
- Powiadomienia SMS po otwarciu pozycji (przez Twilio)

## Wymagania

- Java 17
- Konto na giełdzie BloFin (oraz klucze API)
- Konto Twilio (opcjonalnie, do powiadomień SMS)

## Konfiguracja

### Zmienne środowiskowe

Aplikacja wymaga następujących zmiennych środowiskowych:

```
# Wymagane
BLOFIN_API_KEY=twój_klucz_api
BLOFIN_API_SECRET=twój_sekret_api
BLOFIN_API_PASSPHRASE=twoje_hasło_api
MIN_USDT_AMOUNT_FOR_TRADE=50.0   # minimalna kwota w USDT dla transakcji
RETRACEMENT_DIVIDER=2   # dzielnik dla trailing stop w strategii scalp (domyślnie 2)

# Opcjonalne - powiadomienia SMS przez Twilio
TWILIO_ACCOUNT_SID=twoje_sid_konta_twilio
TWILIO_AUTH_TOKEN=twój_token_uwierzytelniający_twilio
TWILIO_PHONE_NUMBER=numer_telefonu_twilio_z_którego_będą_wysyłane_sms
TWILIO_RECIPIENT_PHONE=twój_numer_telefonu_który_będzie_otrzymywał_powiadomienia
TWILIO_NOTIFICATIONS_ENABLED=true   # włączenie powiadomień SMS (domyślnie false)
```

# Budowanie obrazu Docker

   ```bash
   docker build -t blofin-trade .
   ```


   ```bash
   docker rmi blofin-trade
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

### Integracja z BloFin API

#### Pobieranie otwartych pozycji z BloFin

```bash
curl -X GET http://localhost:8888/api/blofin/positions/open
```

#### Pobieranie salda konta z BloFin

```bash
curl -X GET http://localhost:8888/api/blofin/account/balance
```

## Kolekcja Postman

W repozytorium znajduje się kolekcja Postman (`postman_collection.json`), która ułatwia testowanie API.

## Powiadomienia SMS

Aplikacja obsługuje wysyłanie powiadomień SMS po pomyślnym otwarciu pozycji handlowej. Aby włączyć tę funkcję, należy:

1. Utworzyć konto w [Twilio](https://www.twilio.com/)
2. Skonfigurować zmienne środowiskowe (TWILIO_*)
3. Ustawić TWILIO_NOTIFICATIONS_ENABLED=true