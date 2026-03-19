# BloFin Trade - Agent Coding Guide

## Project Overview
Spring Boot 3.2.4 REST API for crypto trading on BloFin exchange (derivatives market). Core features: position management, API integration with HMAC-SHA256 signing, limit order tracking with partial take-profits, SMS notifications (Twilio), and trade history logging.

**Stack**: Java 17 | Spring Data JPA | PostgreSQL | OkHTTP3 | Twilio SDK | Lombok

---

## Architecture Essentials

### Service Layer Structure
- **BlofinApiClient**: Low-level REST client with HMAC-SHA256 request signing. Handles all exchange API calls to `/api/v1/` endpoints (positions, orders, leverage, balance). Uses `TreeMap<String, String>` for query params (for signature generation stability).
- **BlofinIntegrationService**: High-level orchestration layer. Applies trading logic (scalp strategies, advanced market positions), validates position amounts against `min-usdt-amount-for-trade`, persists trades to `TradeHistory`, and triggers notifications.
- **LimitOrderService + LimitOrderTracker**: Manages pending limit orders stored in PostgreSQL with one-to-many relationship to partial take-profit levels. Tracker runs scheduled checks every 30 seconds (configurable via `limit-order.tracker.check-interval`).
- **TwilioNotificationService**: Sends SMS after successful position opens (only when `twilio.notifications.enabled=true`).

### Data Models
- **LimitOrder**: Entity with orderId, symbol, side, leverage, entryPrice, stopLoss, status (PENDING/FILLED). One-to-many with LimitOrderTakeProfit.
- **LimitOrderTakeProfit**: Partial exit levels mapped by position (key=1,2,3..., value=price). Tracks if processed.
- **TradeHistory**: Audit log of all trades with entry/exit prices, P&L, realized PnL, leverage used.

### Key Endpoints
- `GET /api/bybit/positions/open` - fetch active positions from BloFin
- `GET /api/bybit/account/balance` - wallet balance
- `POST /api/bybit/positions/advanced` - open advanced order with partial take-profits (consumes `Map<String, Object>`)
- `POST /api/bybit/positions/scalp` - scalp short position (consumes `ScalpRequestDto`)

---

## Critical Patterns & Conventions

### BloFin API Authentication
All requests require HMAC-SHA256 signatures:
```
prehash = requestPath + method + timestamp + nonce + body
signature = Base64(Hex(HMAC-SHA256(prehash, secretKey)))
```
Headers: `ACCESS-KEY`, `ACCESS-SIGN`, `ACCESS-TIMESTAMP`, `ACCESS-NONCE`, `ACCESS-PASSPHRASE`

**Instrument Format**: Use `"BTC-USDT"` (with hyphen) in BloFin API, not `"BTCUSDT"`. The client auto-converts via `toInstId()` helper.

### Configuration & Env Variables
Required:
- `BLOFIN_API_KEY`, `BLOFIN_API_SECRET`, `BLOFIN_API_PASSPHRASE`
- `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD` (defaults: bybit_trade / postgres / postgres)
- `MIN_USDT_AMOUNT_FOR_TRADE` (default: 50.0)
- `RETRACEMENT_DIVIDER` (default: 2 - used in scalp stop-loss calculation)

Optional (Twilio):
- `TWILIO_ACCOUNT_SID`, `TWILIO_AUTH_TOKEN`, `TWILIO_PHONE_NUMBER`, `TWILIO_RECIPIENT_PHONE`
- `TWILIO_NOTIFICATIONS_ENABLED` (default: false)

All loaded via Spring's `@Value` from `application.properties` or env vars (env vars override).

### Order Placement Workflow
1. Validate USDT amount against `minUsdtAmountForTrade`
2. Set leverage via `setLeverage()` (default 10x on startup)
3. Place market/limit order via `placeOrder()` with treemap params
4. If successful (code="0"), save to `LimitOrder` (status=PENDING)
5. Create partial `LimitOrderTakeProfit` entries from request
6. Send Twilio SMS (if enabled)
7. Save `TradeHistory` entry
8. Scheduler periodically checks filled/cancelled status

### Exception Handling
- **BlofinApiException**: Thrown when API returns code != "0". GlobalExceptionHandler returns HTTP 422 (UNPROCESSABLE_ENTITY) with `{error:true, apiCode, message}` JSON. Used for n8n/workflow integration (avoids Whitelabel 500 HTML).
- Other RuntimeExceptions → HTTP 500 JSON.

### Testing & Local Development
Build: `mvn clean package`
Run locally: `java -jar target/blofin-trade-0.0.1-SNAPSHOT.jar` (respects env vars)
Docker: `docker build -t blofin-trade .` and run with env vars passed
Logs: `src/main/resources/ddl/create_limit_orders_tables.sql` auto-runs on startup (`spring.sql.init.mode=always`)

### Logging Levels
- `com.mulaczos.blofin_trade.service.*` = INFO (configured)
- Use SLF4J via `@Slf4j` Lombok annotation
- Pattern: `%d{yyyy-MM-dd HH:mm:ss.SSSXXX} [%level] %logger{36} : %msg%n`

---

## Common Tasks & Code Locations

| Task | Key File(s) |
|------|-------------|
| Add new BloFin API endpoint | `BlofinApiClient.java` - add method, `BlofinIntegrationService.java` - call it |
| Add trading logic (validation, calculations) | `BlofinIntegrationService.java` - modify `openAdvancedPosition()` or `openScalpShortPosition()` |
| Change order tracking interval | `application.properties`: `limit-order.tracker.check-interval` or `LimitOrderTracker.java` |
| Debug position data flow | Check JSON response shape at `BlofinApiClient` → logged at INFO level |
| Add DB schema column | `src/main/resources/ddl/create_limit_orders_tables.sql` and add `@Column` to model |
| Update REST endpoint | `BlofinController.java` - add `@GetMapping` / `@PostMapping` |

---

## Known Trade-offs & Edge Cases

- **Stateless API client**: No connection pooling optimization; creates new OkHttpClient per instance (fine for current throughput).
- **Partial take-profits**: Order execution checked every 30s via scheduler; may miss rapid fills in sub-second timeframes.
- **Leverage reset**: Set to default 10x on app startup; manual symbol changes not auto-persisted to config.
- **Error responses**: If API returns code != "0", full JSON is logged; structured exception ensures downstream systems see consistent error format.
- **Scalp strategy**: Uses `retracementDivider` to calculate trailing stop from entry price; fixed divider, no dynamic adjustment.

---

## Tips for AI Agents

1. Always generate signatures with `TreeMap` params (sorted by key) for consistency.
2. Instrument names need hyphen conversion (`BTCUSDT` → `BTC-USDT`); check `toInstId()` method exists before refactoring.
3. When adding fields to LimitOrder/TradeHistory, ensure `@Transactional` scope covers all repo saves.
4. Twilio is optional; always check `twilioNotificationService` null-safety and feature flag.
5. PostgreSQL dialect is hardcoded in properties; do not change without updating Hibernate config.

