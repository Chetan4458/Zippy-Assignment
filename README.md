# Zippy Mock Logistics Platform

This repository implements the Zippy logistics aggregation assignment with a React frontend and a Spring Boot backend.

## What’s Included

- Order creation screen, courier comparison screen, and tracking screen.
- Three mock courier APIs with different request/response shapes.
- Normalized rate and shipment status handling in a common Zippy format.
- H2 storage for orders, quotes, shipments, and shipment events.
- Webhook deduplication and invalid-transition protection.
- Docker Compose setup.
- Tests for the core flows.

## Tech Stack

- Frontend: React + Vite
- Backend: Spring Boot
- Database: H2 with Flyway migrations
- Tests: Spring Boot test + MockMvc
- Build tooling: Node.js only for the React frontend

## Run Locally

```bash
npm install
npm run dev
```

Open the React app at `http://localhost:5173`. The Vite dev server proxies API calls to Spring Boot on `http://localhost:8080`.

If you want the backend only:

```bash
npm start
```

## Docker

```bash
docker compose up --build
```

## Database Migrations

The schema is defined in:

- `backend/src/main/resources/db/migration/V1__init.sql`
- `backend/src/main/resources/db/migration/V2__seed.sql`

## API Endpoints

Core Zippy APIs:

- `POST /api/orders`
- `GET /api/orders/{orderId}`
- `GET /api/orders/{orderId}/rates`
- `POST /api/orders/{orderId}/select-carrier`
- `POST /api/orders/{orderId}/create-shipment`
- `GET /api/orders/{orderId}/tracking`
- `POST /api/webhooks/fastship`
- `POST /api/webhooks/quickexpress`
- `POST /api/webhooks/reliable`

Mock courier APIs:

- `POST /fastship/api/v1/rate`
- `POST /quickexpress/rates/check`
- `GET /reliablecourier/shipping-options`
- `POST /fastship/api/v1/shipments`
- `POST /quickexpress/booking/create`
- `PUT /reliablecourier/orders`

## Triggering Shipment Updates

Use either of these:

1. Wait for the automatic carrier progression timer after shipment creation.
2. Call `POST /api/mock-carriers/{orderId}/advance` to push the next status immediately.

The tracking screen also polls for updates every few seconds.

## Testing

```bash
npm test
```

## API Collection

Import `postman/Zippy-API-Collection.json` into Postman for sample requests.

## Sample Data

See `sample-data/sample-order.json`.
