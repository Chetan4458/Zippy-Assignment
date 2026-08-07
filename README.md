# Zippy Logistics Operations Console

Zippy is a single-tenant logistics operations demo. It creates merchant orders, compares normalized quotes from three mock carriers, books shipments, consumes carrier-specific webhooks, tracks delivery state, manages prepaid and cash-on-delivery payments, and exposes operational reports through a React console and Spring Boot API.

The repository is designed to run locally without third-party credentials. The mock carriers, payment lifecycle, H2 database, and sample data are all self-contained.

## Highlights

- Normalized rate comparison across FastShip, QuickExpress, and Reliable Courier.
- Idempotent order creation through the `Idempotency-Key` header.
- Explicit order and shipment state transitions with duplicate-webhook protection.
- Prepaid intent, confirmation, failure, cancellation, and refund operations.
- COD receivable creation, delivery-time collection, cancellation, and RTO handling.
- Append-only payment transaction history with provider and reconciliation references.
- Filterable finance summaries, daily cash-flow trends, payment snapshots, shipment events, and order history.
- Automated or manually driven mock-carrier progression.
- Health checks, repeatable clean installs, container smoke tests, and CI quality gates.

## Architecture

```text
Browser
  |
  +--> Vite dev/preview server (5174/4173) --proxy--> Spring Boot API (8080)
  |                                                   |
  +--> Spring-served production bundle (Docker)       +--> ZippyService
                                                      |      |
Carrier webhook --> normalization --------------------+      +--> JDBC + H2
                                                             |
                                                             +--> async mock carrier calls
                                                                    |
                                                                    +--> FastShip
                                                                    +--> QuickExpress
                                                                    +--> Reliable Courier
```

The frontend source lives under `client/`. Vite writes production assets to `dist/`. The Docker build copies those assets to `/app/public`, where Spring serves them alongside the API. Database schema changes are managed only by Flyway migrations under `backend/src/main/resources/db/migration`.

## Technology and prerequisites

| Component | Baseline |
| --- | --- |
| Java | JDK 21 |
| Maven | 3.9 or newer |
| Node.js | 22.12 or newer; Node 24 is used in CI and Docker |
| npm | 9 or newer |
| Backend | Spring Boot 3, Spring MVC, JDBC, Flyway |
| Frontend | React 19, Vite 8 |
| Database | H2, file-backed locally and in Compose; replace it before production |
| Containers | Docker Engine with Compose v2, optional |

Java, Maven, Node, and npm must be available on `PATH` for local development. Docker is only required for the container workflow.

## Quick start

The normal local bootstrap is one command:

```bash
npm run dev
```

Open `http://localhost:5174`. If React, ReactDOM, or Vite is missing, the launcher first runs the locked `npm ci` install. It then starts Spring on port 8080, waits for `GET /api/health`, and starts Vite on port 5174. Press `Ctrl+C` once to stop both process trees.

Spring and Maven output is streamed directly to the same terminal. If the backend exits or never becomes healthy, the launcher exits nonzero even when a Maven wrapper anomalously reports code 0; the Spring/Flyway message immediately above the launcher summary remains the authoritative cause. Run `npm start` to reproduce a backend-only startup failure.

The unsecured `local` profile binds Spring to `127.0.0.1`. If `SERVER_PORT` is changed, the launcher health probe and Vite proxy follow it automatically unless `VITE_API_PROXY_TARGET` is set explicitly.

For an explicit clean reinstall, or to reproduce CI setup exactly, run `npm ci` before `npm run dev`.

To run the processes separately:

```bash
npm start
npm run dev:frontend
```

Useful endpoints after startup:

- UI: `http://localhost:5174`
- API health: `http://localhost:8080/api/health`
- System overview: `http://localhost:8080/api/system/overview`
- H2-backed API base: `http://localhost:8080/api`

## Commands

| Command | Purpose |
| --- | --- |
| `npm run dev` | Install missing locked frontend dependencies, start backend, wait for health, then start Vite |
| `npm start` | Start only Spring Boot |
| `npm run dev:frontend` | Start only the Vite development server on 5174 |
| `npm run build` | Build frontend assets into `dist/` |
| `npm run preview` | Preview `dist/` on 4173 with API proxies enabled |
| `npm run sbom:frontend` | Generate a CycloneDX frontend SBOM under `artifacts/` |
| `npm run test:frontend` | Run Node-based frontend unit tests |
| `npm run test:backend` | Run the Spring/JUnit integration suite |
| `npm test` | Run frontend and backend tests |
| `npm run package:backend` | Package the executable JAR without rerunning tests |
| `npm run check` | Run all tests, frontend build, and backend packaging |

`npm run package:backend` skips tests intentionally because `npm run check` executes them first.

## Configuration

Spring Boot accepts its normal property and environment-variable forms. These are the main settings used by Zippy:

| Environment variable | Default | Purpose |
| --- | --- | --- |
| `SPRING_PROFILES_ACTIVE` | `local` for source runs; `prod` in the image | Select local conveniences or fail-closed production controls |
| `SERVER_ADDRESS` | `127.0.0.1` locally | Interface on which Spring listens; Compose uses `0.0.0.0` inside its isolated container |
| `SERVER_PORT` | `8080` | Backend HTTP port |
| `SPRING_DATASOURCE_URL` | File-backed `jdbc:h2:file:./data/zippy...` locally | JDBC connection; mandatory in `prod` |
| `SPRING_DATASOURCE_USERNAME` | `sa` | Database user |
| `SPRING_DATASOURCE_PASSWORD` | empty locally | Database password |
| `ZIPPY_API_KEY` | disabled locally; required in `prod` | Raw operational API key sent as `X-API-Key`; use at least 32 random characters |
| `ZIPPY_WEBHOOK_SECRET_FASTSHIP` | disabled locally; required in `prod` | FastShip HMAC secret |
| `ZIPPY_WEBHOOK_SECRET_QUICKEXPRESS` | disabled locally; required in `prod` | QuickExpress HMAC secret |
| `ZIPPY_WEBHOOK_SECRET_RELIABLE` | disabled locally; required in `prod` | Reliable Courier HMAC secret |
| `ZIPPY_WEBHOOK_MAX_AGE_SECONDS` | `300` | Maximum signed-webhook clock skew/replay window |
| `ZIPPY_AUTOMATION_ENABLED` | `true` | Automatically progress newly created shipments |
| `VITE_API_PROXY_TARGET` | Derived from `SERVER_PORT` | Explicit backend target for Vite dev and preview proxies |

For Compose overrides, copy the placeholder-only example to the ignored `.env` file and replace the password before starting:

```bash
cp .env.example .env
docker compose config --quiet
```

In PowerShell, use `Copy-Item .env.example .env`. This file is used for Compose interpolation; `npm run dev` reads environment variables from the invoking shell instead. The checked-in Compose defaults remain available for an isolated local demo when no `.env` file is created.

Example manual-carrier session in PowerShell:

```powershell
$env:ZIPPY_AUTOMATION_ENABLED = 'false'
npm run dev
```

The development-only runtime flag endpoint can simulate carrier quote delays and failures:

```http
POST /api/dev/runtime-flags
Content-Type: application/json

{
  "quickexpressRateDelayMs": 2500,
  "quickexpressRateFailure": true
}
```

Reset those flags with `POST /api/dev/runtime-flags/reset`.

## Database, migrations, and reset behavior

Local Spring runs use a file-backed H2 database under `data/`, so orders survive normal restarts. Use only synthetic data. To reset it, stop every backend process, make a backup if needed, and remove the local H2 files intentionally before restarting; never delete a database while it is open.

The authoritative migrations are:

```text
backend/src/main/resources/db/migration/
  V1__init.sql
  V2__seed.sql
  V3__payments.sql
  V4__cod_payment_lifecycle.sql
  V5__payment_outcomes.sql
  V6__shipment_lookup_integrity.sql
  V7__idempotency_locking.sql
  V8__payment_transaction_ledger.sql
  V9__payment_ledger_hardening.sql
```

`V8__payment_transaction_ledger.sql` is an applied historical migration and is frozen at Flyway checksum `-115305915`. Later actor, replay, refund-reconciliation, constraint, and legacy cash-event work belongs to the forward-only V9 migration; do not fold those changes back into V8.

Docker Compose overrides the datasource URL with a file-backed database at `/app/data/zippy` and stores it in the named `app_data` volume. Normal `docker compose down` and container replacement preserve that data.

To deliberately erase all Compose data and restart from a clean schema:

```bash
docker compose down --volumes
docker compose up --build
```

The `--volumes` operation is destructive and cannot be undone unless the volume was backed up.

## Docker

Run the loopback-only Compose demo and hot-reload frontend together:

```bash
docker compose up --build
```

- Development UI: `http://localhost:5174`
- Production bundle and API: `http://localhost:8080`
- Health: `http://localhost:8080/api/health`

Both published ports bind to `127.0.0.1`, not every network interface. The frontend waits for the application health check before starting. Compose uses `zippy-local` as the local H2 password unless `ZIPPY_DB_PASSWORD` is set.

Build the release-shaped image. The image defaults to the fail-closed `prod` profile; for an explicitly local, loopback-published demo run:

```bash
docker build -t zippy:local .
docker run --rm -p 127.0.0.1:8080:8080 \
  -e SPRING_PROFILES_ACTIVE=local \
  -e SERVER_ADDRESS=0.0.0.0 \
  zippy:local
```

A production-profile container refuses to start without an explicit datasource, an API key, and independent FastShip, QuickExpress, and Reliable webhook secrets. H2 and the single global API key are still demonstration constraints, not a multi-tenant production architecture.

The image build performs a locked npm install, frontend unit tests, frontend production build, backend tests, and backend packaging. The runtime image uses Java 21, runs as a non-root user, and includes an HTTP health check.

## Health contract

`GET /api/health` is unauthenticated and returns HTTP 200 when the application and database are ready.

```json
{
  "status": "UP",
  "database": "UP",
  "timestamp": "2026-08-07T12:00:00Z"
}
```

Additional diagnostic fields may be present. Automation and deployment checks should depend only on HTTP 200 and `status: "UP"`.

## Create an order

`POST /api/orders` accepts a COD or prepaid order and returns the new Zippy order ID plus normalized carrier quotes.

```bash
curl --request POST http://localhost:8080/api/orders \
  --header "Content-Type: application/json" \
  --header "Idempotency-Key: demo-order-10001" \
  --data @sample-data/sample-order.json
```

Core request shape:

```json
{
  "merchantOrderId": "MERCHANT-10001",
  "customer": {
    "name": "Rahul Sharma",
    "phone": "9876543210",
    "email": "rahul@example.com"
  },
  "pickupAddress": {
    "addressLine1": "15 MG Road",
    "city": "Bengaluru",
    "state": "Karnataka",
    "pincode": "560001"
  },
  "deliveryAddress": {
    "addressLine1": "22 Connaught Place",
    "city": "New Delhi",
    "state": "Delhi",
    "pincode": "110001"
  },
  "package": {
    "weightGrams": 1500,
    "lengthCm": 20,
    "widthCm": 15,
    "heightCm": 10
  },
  "paymentType": "COD",
  "codAmount": 2500
}
```

Reusing an `Idempotency-Key` with the same request returns the stored response without creating a second order. Reusing it for a different payload is rejected. Per-key database locks also serialize simultaneous retries across application instances.

## Main API workflow

| Method and path | Purpose |
| --- | --- |
| `POST /api/orders` | Create an order and fetch normalized quotes |
| `GET /api/orders/{orderId}` | Read the order, quotes, shipment, and events |
| `GET /api/orders/history?limit=20&offset=0` | Read recent orders |
| `GET /api/orders/{orderId}/rates?sortBy=lowest` | Sort quotes by lowest, fastest, or carrier |
| `POST /api/orders/{orderId}/select-carrier` | Select an exact returned quote |
| `POST /api/orders/{orderId}/create-shipment` | Book the selected carrier shipment |
| `POST /api/orders/{orderId}/cancel` | Cancel before carrier pickup |
| `GET /api/orders/{orderId}/tracking` | Read current order and shipment state |
| `GET /api/orders/{orderId}/events?limit=20&offset=0` | Read paginated shipment events |
| `POST /api/mock-carriers/{orderId}/advance` | Advance one valid happy-path state |
| `POST /api/mock-carriers/{orderId}/delivery-failed` | Simulate an NDR/delivery failure |
| `POST /api/mock-carriers/{orderId}/rto` | Simulate return to origin |
| `GET /api/reports/summary` | Read filterable operational and finance metrics |
| `GET /api/reports/payments?limit=20&offset=0` | Read filtered, paginated payment snapshots |
| `GET /api/reports/daily-trend` | Read daily collected/refunded cash flow |

Carrier selection body:

```json
{
  "carrierCode": "FASTSHIP",
  "serviceCode": "FAST-AIR",
  "quotedAmount": 182.90
}
```

Use the carrier, service, and exact amount from the returned quote rather than hardcoding them.

## Shipment lifecycle

```text
ORDER_CREATED -> CARRIER_SELECTED -> SHIPMENT_CREATED -> PICKED_UP
                                                        |
                                                        v
                                                   IN_TRANSIT
                                                        |
                                                        v
                                                OUT_FOR_DELIVERY
                                                  |           |
                                                  v           v
                                             DELIVERED   DELIVERY_FAILED
                                                               |
                                                    +----------+----------+
                                                    |                     |
                                                    v                     v
                                               IN_TRANSIT                RTO
```

- Cancellation is allowed before pickup.
- `DELIVERY_FAILED` may retry through `IN_TRANSIT` or terminate as `RTO`.
- `DELIVERED`, `RTO`, and `CANCELLED` are terminal.
- Duplicate carrier event IDs are accepted as no-ops.
- Invalid state transitions return HTTP 409.

## Payments

COD orders automatically receive an `AWAITING_COLLECTION` receivable. Collection is valid only after delivery. Cancellation or RTO voids an uncollected COD receivable.

Prepaid flow:

1. Create a prepaid order.
2. Select a carrier quote.
3. `POST /api/payments` using the selected quote amount and `INR`.
4. Confirm, fail, or cancel the pending intent.
5. Create the shipment only after the prepaid payment succeeds.
6. Refund only after the shipment is cancelled, returned to origin, or delivered.

The selected carrier and quote are frozen against material changes as soon as any prepaid intent exists; repeating the identical selection is a no-op. Intent creation requires `INR` and an amount exactly equal to the selected quote (HTTP 422 otherwise). Confirmation locks the payment and selected shipment and revalidates that amount again; a stale or changed amount is rejected with HTTP 409 rather than captured. Shipment creation also requires a successful prepaid payment whose amount still matches the selected charge.

```json
{
  "orderId": "ZPY-ORD-10001",
  "amount": 182.90,
  "currency": "INR"
}
```

| Method and path | Purpose |
| --- | --- |
| `POST /api/payments` | Create or reuse a pending prepaid intent |
| `GET /api/payments/{paymentId}` | Read one payment |
| `GET /api/orders/{orderId}/payments` | Read payments for an order |
| `POST /api/payments/{paymentId}/confirm` | Confirm a pending prepaid intent |
| `POST /api/payments/{paymentId}/fail` | Fail a pending prepaid intent |
| `POST /api/payments/{paymentId}/cancel` | Cancel a pending prepaid intent |
| `POST /api/payments/{paymentId}/refund` | Refund an eligible terminal-shipment prepaid payment |
| `POST /api/payments/{paymentId}/collect` | Collect COD after delivery |
| `GET /api/payments/{paymentId}/transactions?limit=50&offset=0` | Read one payment's immutable transaction history |
| `GET /api/orders/{orderId}/payment-transactions?limit=50&offset=0` | Read transaction history across an order's payments |

Confirmation, cancellation, refund, and COD collection accept an optional audit body:

```json
{
  "reason": "Synthetic provider authorization completed",
  "provider": "ZIPPY_DEMO_GATEWAY",
  "reference": "capture-PAY-1234",
  "reconciliationReference": "recon-20260807-PAY-1234"
}
```

For confirmation and collection, `reference` becomes `providerReference`; for a refund it becomes `refundReference`. Capture or COD reconciliation remains in `reconciliationReference`, while refund reconciliation is stored separately in `refundReconciliationReference`, so refund processing never overwrites the original settlement evidence. Failure uses `{ "code", "reason", "reference" }`. The response also exposes `provider`, all four reference fields, `capturedAt`, `collectedAt`, and `refundedAt` on the payment snapshot.

Send a stable `Idempotency-Key` of at most 128 characters with payment-intent creation and every payment mutation. This key namespace is global across payment operations. The server stores a request hash and the exact serialized response with the ledger event. Repeating an intent key with the same normalized order, amount, and currency—or a mutation key with the same payment, event, and normalized audit body—returns that original response without appending another transaction, even if the payment has changed since. Reusing the key for another intent payload, payment, event, or audit body returns HTTP 409. Keys and audit bodies remain optional for compatibility, but integrations should always supply both.

Refunds are full refunds only: a successful refund sets `refundedAmount` to the original payment amount and records `REFUNDED`. `REFUND_PENDING` is recorded when an order/shipment transition first makes repayment due. Partial refund amounts are not accepted by this demo.

### Payment transaction ledger

Lifecycle mutations append transaction rows; there is no transaction update or delete API. This is an application-level audit trail, not a cryptographically signed or externally reconciled ledger. A typical page is:

```json
{
  "paymentId": "PAY-1234ABCD",
  "orderId": "ZPY-ORD-10001",
  "limit": 50,
  "offset": 0,
  "totalTransactions": 3,
  "transactions": [
    {
      "transactionId": "PTX-1234ABCD",
      "paymentId": "PAY-1234ABCD",
      "orderId": "ZPY-ORD-10001",
      "eventType": "CAPTURED",
      "previousStatus": "PENDING",
      "resultingStatus": "SUCCEEDED",
      "amount": 182.90,
      "currency": "INR",
      "provider": "ZIPPY_DEMO_GATEWAY",
      "providerReference": "capture-PAY-1234",
      "reconciliationReference": "recon-20260807-PAY-1234",
      "reason": "Synthetic provider authorization completed",
      "actor": "LOCAL_OPERATOR",
      "createdAt": "2026-08-07T10:30:00Z"
    }
  ]
}
```

Order-level history has `paymentId: null` and the same transaction shape. Event types are `INTENT_CREATED`, `COD_AWAITING_COLLECTION`, `CAPTURED`, `FAILED`, `CANCELLED`, `AUTO_CANCELLED`, `AUTO_VOIDED`, `REFUND_PENDING`, `REFUNDED`, and `COD_COLLECTED`; `STATE_SNAPSHOT` identifies state captured by migration V8.

`actor` is shared-channel attribution, not individual identity. Local API calls use `LOCAL_OPERATOR`, production API-key calls use the shared `zippy-api-key` principal, and automated lifecycle transitions use `ZIPPY_SYSTEM`. Migration-generated snapshot and inferred cash rows use `MIGRATION`; `LEGACY_UNKNOWN` marks a pre-V9 operation whose actor was never stored and could not be reconstructed safely. These values cannot distinguish two people sharing the API key and do not provide roles or approval separation. The database also retains keyed-event request hashes and serialized replay responses, but those internal idempotency fields are not exposed by the ledger endpoints.

V8 introduced the transaction ledger and created one `STATE_SNAPSHOT` for each existing payment. V9 preserves that historical migration unchanged, adds the hardened actor and global replay fields, separates refund reconciliation, and reconstructs missing cash events so upgraded reports do not silently lose prior collections and refunds. The reconstructed cash rows are explicitly inferred, not provider evidence:

| Migration row | Timestamp source | Stored reason |
| --- | --- | --- |
| V9 prepaid `CAPTURED` for legacy `SUCCEEDED` | Payment `updatedAt` | `Legacy prepaid capture inferred during payment ledger migration` |
| V9 prepaid `CAPTURED` for legacy `REFUND_PENDING`/`REFUNDED` | Payment `createdAt` | `Legacy prepaid capture inferred during payment ledger migration` |
| V9 legacy `COD_COLLECTED` | Payment `updatedAt` | `Legacy COD collection inferred during payment ledger migration` |
| V9 legacy `REFUNDED` | Payment `updatedAt` | `Legacy full refund inferred during payment ledger migration` |
| V8 `STATE_SNAPSHOT` for every pre-existing payment | Payment `updatedAt` | `State snapshot created by payment ledger migration` |

After V9, the snapshot and inferred cash rows use actor `MIGRATION`. A migrated refund receives a synthetic `MIGRATED-REFUND-{paymentId}` reference when the legacy snapshot had none.

## Financial reports

The report endpoints share optional `from`, `to`, `status`, `method`, and `carrier` filters. Payment snapshots also accept `search`, `limit`, and `offset`.

```http
GET /api/reports/summary?from=2026-08-01&to=2026-08-07&status=SUCCEEDED&method=COD&carrier=FASTSHIP
GET /api/reports/payments?from=2026-08-01&to=2026-08-07&search=COD-&limit=50&offset=0
GET /api/reports/daily-trend?from=2026-08-01&to=2026-08-07&method=PREPAID
```

- Dates are strict `YYYY-MM-DD` UTC calendar dates and are inclusive at the API boundary. A reversed or malformed range returns HTTP 400.
- Responses make the exact half-open query window explicit as `filters.fromInclusive` and `filters.toExclusive`; for `to=2026-08-07`, `toExclusive` is `2026-08-08T00:00:00Z`.
- Status is one of `PENDING`, `SUCCEEDED`, `FAILED`, `CANCELLED`, `AWAITING_COLLECTION`, `VOIDED`, `REFUND_PENDING`, or `REFUNDED`.
- Method is `PREPAID` or `COD`. Carrier codes are case-insensitive, 2-32 characters, and limited to letters, numbers, `_`, and `-`.
- `search` is a case-insensitive literal substring of at most 64 characters across payment/order identifiers and snapshot or ledger audit references. SQL pattern/escape characters such as `%`, `_`, and `!` are treated literally, not as operators.

Cash flow and balances intentionally use different clocks. `grossCollected`, `refunds`, `netCollected`, and the daily trend use immutable transaction `createdAt` event time. The payment list and current-state balances use the payment's `createdAt` cohort. This means a refund today for a payment created last month appears in today's cash-flow trend, while that payment remains in last month's snapshot cohort.

| Metric | Exact meaning |
| --- | --- |
| `grossCollected` | `CAPTURED` prepaid plus `COD_COLLECTED` ledger amounts before refunds |
| `refunds` | Full-refund ledger amounts, reported as a positive value |
| `netCollected` | `grossCollected - refunds` |
| `pendingIntent` | Count and amount of current prepaid `PENDING` snapshots |
| `failed` | Count and amount of current `FAILED` snapshots |
| `voided` | Count and amount of current `VOIDED` COD snapshots |
| `codOutstanding` | Count and amount of current COD `AWAITING_COLLECTION` snapshots |
| `shipmentCost` | Selected quoted charges for shipments not cancelled or returned to origin |
| `costedShipmentCount` | Shipment count using the same non-cancelled/non-RTO predicate as `shipmentCost` |
| `activeShipmentCount` | Costed shipments that are not yet delivered |

The finance object also declares its `currency`. `totalShippingCost` and `carrierBreakdown[].shippingCost` are operational cost measures, never shipping revenue. `dailyTrend` rows contain `date`, `grossCollected`, `refunds`, `netCollected`, and `transactions`. `paymentsByStatus` is a snapshot count; `/api/reports/payments` returns `{ filters, limit, offset, totalPayments, payments, finance }`.

Summary order/shipment totals and order breakdowns are deliberately all-time. `totalPayments`, finance, trends, and payment/carrier breakdowns follow the supplied filters. The response states the broader contract exactly as `scope: { operationalTotals: "ALL_TIME", finance: "FILTERED", cashFlowDateBasis: "PAYMENT_TRANSACTION_CREATED_AT_UTC", balanceDateBasis: "PAYMENT_CREATED_AT_UTC", shipmentDateBasis: "SHIPMENT_CREATED_AT_UTC" }`. Consumers should use `scope` rather than assuming every top-level value shares the finance filter.

The console's CSV is generated in the browser; there is no server CSV endpoint. "Export shown rows" exports only the currently visible filtered page, at most 50 of `totalPayments`. Its columns are Payment ID, Order ID, Method, Provider, Provider reference, Reconciliation reference, Refund reference, Refund reconciliation reference, Amount, Refunded amount, Net cash collected, Currency, Status, Failure code, Failure reason, Created at, Updated at, Captured at, Collected at, and Refunded at. Monetary cells use fixed two-decimal strings. Net cash is status-aware: it is `amount - refundedAmount` only for collected/refund states and `0.00` for pending, failed, cancelled, voided, or uncollected COD snapshots. Every cell is quoted and escaped, and values beginning with optional whitespace followed by `=`, `+`, `-`, or `@` are neutralized to reduce spreadsheet-formula injection. The CSV is a convenience snapshot, not a complete ledger or reconciliation artifact; use the JSON transaction endpoints for audit evidence.

The reports screen requests the summary and payment page together and commits them to the UI only after both requests succeed. A partial failure leaves the previous coherent pair visible and reports the refresh error, rather than combining filters or timestamps from two refresh cycles.

All providers and references in this repository are synthetic labels. No route contacts a payment processor, settles funds, verifies a provider transaction, or performs bank reconciliation. Never use these flows for real money; production remains a no-go until a compliant provider, independent reconciliation, access controls, and auditable secret management are implemented.

## Webhooks and mock carrier APIs

Canonical webhook routes:

- `POST /api/webhooks/fastship`
- `POST /api/webhooks/quickexpress`
- `POST /api/webhooks/reliable`

`POST /api/webhooks/{carrier}` is an alias limited to `fastship`, `quickexpress`, or `reliable`.

In `prod`, every webhook request must include:

- `X-Zippy-Webhook-Timestamp`: Unix epoch seconds within the configured replay window;
- `X-Zippy-Webhook-Event-Id`: 1-120 characters from `A-Z`, `a-z`, `0-9`, `.`, `_`, `:`, or `-`, matching the provider event ID in the JSON body; and
- `X-Zippy-Webhook-Signature`: `sha256=` followed by the 64-character hexadecimal HMAC-SHA256 digest.

Calculate the digest with the carrier-specific secret over the exact bytes `timestamp + "." + eventId + "." + rawRequestBody`. Do not reformat or re-encode the JSON after signing. The local profile skips this verification for sample-data convenience; production rejects missing, malformed, stale, mismatched, or incorrectly signed requests.

| Carrier | Tracking field | Status field |
| --- | --- | --- |
| FastShip | `shipment_id` | `event_code` |
| QuickExpress | `awb` | `event.type` |
| Reliable Courier | `trackingCode` | numeric `statusId` |

Each event should include a stable carrier event ID. Zippy persists the raw payload and normalized status, and deduplicates by shipment plus carrier event ID.

Mock carrier endpoints used internally are also available for inspection:

- `POST /fastship/api/v1/rate`
- `POST /quickexpress/rates/check`
- `GET /reliablecourier/shipping-options`
- `POST /fastship/api/v1/shipments`
- `POST /quickexpress/booking/create`
- `PUT /reliablecourier/orders`

## Errors and status codes

API errors use one JSON envelope:

```json
{
  "timestamp": "2026-08-07T10:30:00Z",
  "status": 400,
  "message": "Invalid webhook payload",
  "details": [
    "event_time is required"
  ],
  "requestId": "req-1234abcd"
}
```

`details` is an empty array when there are no field-level details. `requestId` can be supplied to support when correlating a failed request with server logs.

| Status | Meaning |
| --- | --- |
| 400 | Malformed JSON, invalid webhook shape, or request validation failure |
| 404 | Unknown order, shipment tracking key, or payment |
| 409 | Invalid lifecycle transition, duplicate business operation, or idempotency conflict |
| 422 | Semantically unsupported status, currency, or payment amount |
| 500 | Unexpected server failure |

## Postman and sample data

Import `postman/Zippy-API-Collection.json`. Its `baseUrl` defaults to `http://localhost:8080`; set its `apiKey` variable when exercising authenticated API routes. Its numbered COD, prepaid, ledger, and reporting folders capture IDs and current UTC report dates automatically and assert accounting identities and exact response boundaries. Run requests in folder order against a fresh database; disable carrier automation for the deterministic four-step COD delivery flow. The included webhook examples target the unsigned local profile; add the three signed headers above when testing `prod`.

Available request bodies:

- `sample-data/sample-order.json`: valid COD order.
- `sample-data/prepaid-order.json`: valid prepaid order.
- `sample-data/payment-intent.json`: prepaid payment intent template; replace the order ID and amount.
- `sample-data/payment-confirmation.json`: synthetic provider capture audit metadata.
- `sample-data/payment-failure.json`: synthetic failed-provider outcome metadata.
- `sample-data/payment-refund.json`: full-refund audit metadata.
- `sample-data/cod-collection.json`: delivered COD receipt and reconciliation metadata.
- `sample-data/invalid-order.json`: intentionally invalid validation example.
- `sample-data/fastship-picked-up-webhook.json`: FastShip pickup event.
- `sample-data/quickexpress-out-for-delivery-webhook.json`: QuickExpress OFD event.
- `sample-data/reliable-delivered-webhook.json`: Reliable Courier delivery event.

Webhook examples contain the mock carriers' default tracking identifiers. Create the matching carrier shipment first. Disable automation when stepping through webhook events manually to avoid racing the automated lifecycle.

## Testing and CI

Run the complete local gate before committing:

```bash
npm ci
npm run check
```

The GitHub Actions workflow in `.github/workflows/ci.yml` performs:

1. Locked npm installation and a high-severity dependency audit.
2. Frontend unit tests and production build.
3. Backend integration tests and executable JAR packaging on Java 21.
4. CycloneDX SBOM generation for npm and Maven dependencies, retained as a workflow artifact.
5. A canary-verified Gitleaks scan of the full Git history.
6. Docker Compose configuration validation and a complete multi-stage image build.
7. Container health, UI, and API smoke tests.

Dependabot checks npm, Maven, GitHub Actions, and Docker dependencies weekly. Review automated upgrades and their CI evidence before merging them.

Generated frontend assets, Maven output, local database files, logs, editor settings, and dependency directories are ignored.

## Security and demo boundary

The local profile intentionally disables authentication and carrier signatures, binds Spring to `127.0.0.1`, and is only for synthetic development data. The `prod` profile fails startup unless a strong operational API key and three independent webhook secrets are configured. Operational API routes then require `X-API-Key`; carrier webhooks require HMAC-SHA256 signatures, timestamp freshness, and event identifiers; developer and mock mutation routes are denied. CORS origins are configurable and default to local origins only outside production.

These controls protect the demo boundary but do not provide human identity, roles, or tenant isolation. Do not expose it directly to an untrusted network. A complete production design must still add:

- merchant and tenant identity with data isolation;
- role-based controls for operations, finance, support, and developer endpoints;
- carrier-specific secret rotation and provider-integrated webhook key management;
- real payment-provider integration with secrets held outside source control;
- protected retention and independent reconciliation for the application transaction ledger and runtime changes;
- rate limiting, request-size limits, security headers, TLS termination, backups, and monitoring;
- a production database rather than embedded H2.

Never commit environment dumps, credential-manager diagnostics, `.env` files, database files, or logs. If sensitive material was previously published, removing the current file is not sufficient: purge it from repository history and rotate potentially exposed credentials.

Report suspected vulnerabilities privately according to [SECURITY.md](SECURITY.md). Before any non-local deployment, complete the explicit no-go controls in [the production-readiness guide](docs/PRODUCTION_READINESS.md).

## Release and operations

- [Operations runbook](docs/OPERATIONS_RUNBOOK.md): one-command startup, Compose validation, health checks, troubleshooting, consistent H2 backup and restore, and rollback boundaries.
- [Production-readiness guide](docs/PRODUCTION_READINESS.md): current no-go decision, required security/data/reliability controls, and release gates.
- [Security policy](SECURITY.md): private reporting, supported scope, and exposed-secret response.

The runbook's file-copy backup is only for the stopped local H2 demo. A production database requires encrypted automated backups, point-in-time recovery, and rehearsed recovery objectives.

## Troubleshooting

- `npm ci` says the manifest and lockfile differ: regenerate `package-lock.json` with the intended npm version and commit both files together.
- Port 8080 or 5174 is busy: stop the conflicting process; Vite uses `strictPort` and will not silently choose another port.
- Frontend API calls fail: verify `GET http://127.0.0.1:8080/api/health` and check `VITE_API_PROXY_TARGET`.
- Secured API calls return 401: enter the configured `X-API-Key` in the session-only connection panel; local mode does not require one.
- Manual webhooks return HTTP 409: disable carrier automation and replay events from a fresh order in valid state order.
- Compose data appears stale: inspect the `app_data` volume; use `docker compose down --volumes` only when intentional data loss is acceptable.
- Backend startup reports Flyway validation failure: stop all backend processes and preserve the database before investigating. Never edit or "repair" an applied migration to force a checksum match. Restore the historical migration source and put new schema work in a higher-versioned migration, or restore an application/database pair from the same recorded migration boundary. Delete local H2 files only when the data is disposable and an intentional reset is acceptable.
- Java compilation fails: verify `java -version` and `mvn -version` both use JDK 21.

## Repository layout

```text
backend/                         Spring Boot application, tests, and Flyway migrations
client/                          React source and frontend tests
postman/                         Runnable API collection
sample-data/                     Valid, invalid, payment, and webhook examples
scripts/dev.mjs                  Cross-platform local process supervisor
.github/dependabot.yml           Weekly npm, Maven, Actions, and Docker updates
.github/workflows/ci.yml         Tests, scans, SBOMs, builds, and smoke checks
docs/                            Operations and production-readiness guidance
.env.example                     Placeholder-only local Compose overrides
SECURITY.md                      Private reporting and secret-response policy
Dockerfile                       Test-aware multi-stage production image
docker-compose.yml               Loopback development stack with persistent H2
vite.config.js                   Dev/preview proxies and dist production output
```
