# Zippy Logistics Operations Console

Zippy is a single-tenant merchant shipping operations console. It creates orders, compares three carrier quotes, creates shipments, normalizes carrier webhooks, tracks delivery, manages prepaid and COD payment lifecycles, and exposes operational reports.

## Stack and local configuration

- Frontend: React 19, Vite 8, `client/src/main.jsx`, `client/src/styles.css`.
- Backend: Spring Boot 3.3.5, Java, `backend/src/main/java/com/zippy/backend`.
- Database: H2 with Flyway migrations under `backend/src/main/resources/db/migration`.
- Backend port: `8080` (`server.port` in `backend/src/main/resources/application.yml`).
- Frontend port: `5174` (`vite.config.js`).
- Vite proxies `/api`, `/fastship`, `/quickexpress`, and `/reliablecourier` to `VITE_API_PROXY_TARGET`, defaulting to `http://127.0.0.1:8080`.
- No Spring profile is required. H2 is in-memory, so restarting the backend resets data and reruns Flyway.
- Carrier automation is enabled by default through `zippy.automation-enabled=true`; set it to `false` to require manual webhook progression.
- CORS is development-only: localhost and 127.0.0.1 origins are allowed.
- There is currently no authentication or authorization. This is an intentional single-tenant demo/assignment boundary; production deployment must add identity, tenant isolation, roles, webhook signing, and audit authorization.

## Run and test

```bash
npm install
npm run dev
```

Open `http://localhost:5174`.

```bash
npm run build
npm test
```

`npm test` runs `mvn -q -f backend/pom.xml test`.

## Data model

The primary relationships are:

```text
Order 1 ── * ShippingQuote
Order 1 ── 0..1 Shipment 1 ── * ShipmentEvent
Order 1 ── * Payment
Order 1 ── 0..1 IdempotencyKey response
```

- `orders`: merchant/customer/address/package data, payment type, COD amount, order status, timestamps.
- `shipping_quotes`: normalized carrier/service quotes belonging to an order.
- `shipments`: selected carrier, tracking identifiers, quoted amount snapshot, and current shipment status.
- `shipment_events`: immutable normalized carrier events. `(shipment_id, carrier_event_id)` is unique for webhook deduplication.
- `payments`: prepaid intents and COD receivables. Stores amount, currency, payment method, lifecycle status, collection stage, failure details, refund amount, and timestamps.
- `idempotency_keys`: request hash and serialized response for idempotent order creation.
- `app_meta`: the mock order-number sequence.

## Order and shipment lifecycle

The state machine is intentionally explicit:

```text
ORDER_CREATED ──> CARRIER_SELECTED ──> SHIPMENT_CREATED ──> PICKED_UP
       │                 │                     │                  │
       └─ CANCELLED      └─ CANCELLED          └─ CANCELLED        └─> IN_TRANSIT
                                                                    │
                                                                    v
                                                              OUT_FOR_DELIVERY
                                                               │            │
                                                               │            └─> DELIVERY_FAILED
                                                               │                  │       │
                                                               │                  │       └─> RTO
                                                               │                  └─> IN_TRANSIT (retry)
                                                               └─> DELIVERED
```

Rules:

- Cancellation is allowed before pickup: `ORDER_CREATED`, `CARRIER_SELECTED`, or `SHIPMENT_CREATED`.
- Cancellation after pickup is rejected because the parcel is already in carrier custody.
- `DELIVERY_FAILED` is an out-for-delivery failure/NDR. It can retry through `IN_TRANSIT` or terminate as `RTO`.
- `RTO` and `DELIVERED` are terminal shipment states.
- Duplicate events for the same shipment and carrier event ID are accepted as no-ops.
- Invalid transitions return `409 Conflict`.

## Payment lifecycle

Prepaid payments:

```text
PENDING ──> SUCCEEDED ──> REFUND_PENDING ──> REFUNDED
   │             │
   ├─> FAILED    └─> REFUNDED (manual refund endpoint)
   └─> CANCELLED
```

COD payments:

```text
AWAITING_COLLECTION ──> SUCCEEDED
          │
          └─> VOIDED on cancellation or RTO
```

- COD is created when a COD order is created and represents a receivable.
- COD collection is allowed only after shipment status becomes `DELIVERED`.
- A delivery failure does not void COD because the carrier may retry delivery.
- RTO or cancellation voids an uncollected COD receivable.
- A pending prepaid payment may be failed or cancelled.
- A succeeded prepaid payment may be refunded. An RTO moves a succeeded prepaid payment to `REFUND_PENDING` so operations can complete the refund explicitly.

## Authentication boundary

The current app has no auth: every local caller can create orders, change runtime flags, send webhooks, and operate payments. Treat all `/api/dev/**`, mock carrier APIs, payment operation endpoints, and webhook endpoints as trusted local-demo surfaces only. A production version should add:

- merchant/tenant identity on orders and payments;
- role checks for operations, finance, and support actions;
- signed carrier webhooks with replay protection;
- gateway/provider credentials kept server-side;
- audit entries for cancellation, collection, failure, and refund actions.

## Core API shapes

Create order request:

```json
{
  "merchantOrderId": "MERCHANT-10001",
  "customer": { "name": "Rahul Sharma", "phone": "9876543210", "email": "rahul@example.com" },
  "pickupAddress": { "addressLine1": "15 MG Road", "city": "Bengaluru", "state": "Karnataka", "pincode": "560001" },
  "deliveryAddress": { "addressLine1": "22 Connaught Place", "city": "New Delhi", "state": "Delhi", "pincode": "110001" },
  "package": { "weightGrams": 1500, "lengthCm": 20, "widthCm": 15, "heightCm": 10 },
  "paymentType": "COD",
  "codAmount": 2500
}
```

Important endpoints:

- `POST /api/orders` — create order and fetch normalized quotes. Supports `Idempotency-Key`.
- `GET /api/orders/{orderId}` — order, quotes, selected shipment, and events.
- `GET /api/orders/{orderId}/rates?sortBy=lowest|fastest|carrier` — rates.
- `POST /api/orders/{orderId}/select-carrier` — body `{ carrierCode, serviceCode, quotedAmount }`.
- `POST /api/orders/{orderId}/create-shipment` — create the carrier shipment.
- `POST /api/orders/{orderId}/cancel` — cancel before pickup.
- `GET /api/orders/{orderId}/tracking` — current order/shipment state.
- `GET /api/orders/{orderId}/events?limit=10&offset=0` — paged shipment events.
- `GET /api/orders/{orderId}/payments` — payment records for the order.
- `POST /api/payments` — create a prepaid intent; amount must match the selected shipment charge in INR.
- `POST /api/payments/{paymentId}/confirm` — confirm a pending prepaid intent.
- `POST /api/payments/{paymentId}/fail` — mark a pending prepaid intent failed; optional body `{ "code", "reason" }`.
- `POST /api/payments/{paymentId}/cancel` — cancel a pending payment.
- `POST /api/payments/{paymentId}/refund` — refund a succeeded prepaid payment.
- `POST /api/payments/{paymentId}/collect` — collect COD after delivery.
- `GET /api/reports/summary` — aggregate order, shipment, carrier, COD, and payment metrics.
- `GET /api/reports/payments?limit=10&offset=0` — paginated payment history.

Example payment response:

```json
{
  "paymentId": "COD-AB12CD34",
  "orderId": "ZPY-ORD-10001",
  "amount": 2500.00,
  "currency": "INR",
  "status": "AWAITING_COLLECTION",
  "paymentMethod": "COD",
  "collectionStage": "DELIVERY",
  "failureCode": null,
  "failureReason": null,
  "refundedAmount": 0.00,
  "createdAt": "2026-08-02T10:00:00",
  "updatedAt": "2026-08-02T10:00:00"
}
```

## Webhooks

The three carrier-specific routes are canonical:

- `POST /api/webhooks/fastship`
- `POST /api/webhooks/quickexpress`
- `POST /api/webhooks/reliable`

`POST /api/webhooks/{carrier}` is only an alias for those same three routes. `{carrier}` must be `fastship`, `quickexpress`, or `reliable`; it is not a fourth carrier or separate workflow.

Carrier payload normalization:

- FastShip identifies shipments by `shipment_id` and uses `event_code`.
- QuickExpress identifies shipments by `awb` and uses `event.type`.
- Reliable identifies shipments by `trackingCode` and uses numeric `statusId`.

Webhook response example:

```json
{ "duplicate": false, "status": "DELIVERY_FAILED", "carrierEventId": "NDR-123" }
```

Use `POST /api/mock-carriers/{orderId}/advance` to progress the happy path in the local demo. For failure branches, send a carrier webhook with the appropriate mapped status, then either retry with the next valid event or send RTO.

## Mock carrier APIs

- `POST /fastship/api/v1/rate`
- `POST /quickexpress/rates/check`
- `GET /reliablecourier/shipping-options`
- `POST /fastship/api/v1/shipments`
- `POST /quickexpress/booking/create`
- `PUT /reliablecourier/orders`

Postman examples are in `postman/Zippy-API-Collection.json`; sample order data is in `sample-data/sample-order.json`.
