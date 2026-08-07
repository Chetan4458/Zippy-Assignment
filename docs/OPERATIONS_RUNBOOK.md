# Operations Runbook

## Scope

This runbook covers the local developer launcher and the Docker Compose demonstration stack. It does not turn the embedded H2 deployment into a production service. Before any broader deployment, complete [the production-readiness gate](PRODUCTION_READINESS.md).

## Local startup

Prerequisites are Java 21, Maven 3.9+, Node.js 22.12+, and npm 9+ on `PATH`.

From the repository root, the normal bootstrap is one command:

```bash
npm run dev
```

The launcher checks for React, ReactDOM, and Vite. When any is missing, it runs a locked `npm ci` automatically, then starts Spring, waits for `GET /api/health`, and starts Vite. Use `npm ci` explicitly first when you need a clean, deterministic reinstall or are reproducing CI. Spring/Maven output remains attached to the terminal. An exit before health or a 60-second readiness timeout makes the launcher exit nonzero; even if the Maven wrapper reports code 0, that does not override the failed readiness check. Use the Spring/Flyway output immediately above the launcher summary as the root cause, or run `npm start` to reproduce the backend alone.

Validate readiness at:

```bash
curl --fail http://127.0.0.1:8080/api/health
```

A ready response has HTTP 200 and `status` equal to `UP`. Stop both process trees with one `Ctrl+C` in the launcher terminal.

## Compose startup

The checked-in defaults are sufficient for an isolated demo. To override them, copy the example and replace its password placeholder before startup:

```bash
cp .env.example .env
docker compose config --quiet
docker compose up --build --detach
docker compose ps
curl --fail http://127.0.0.1:8080/api/health
```

PowerShell uses `Copy-Item .env.example .env` instead of `cp`. `docker compose config --quiet` validates interpolation without printing the rendered configuration; avoid sharing non-quiet configuration output because it can contain secrets.

The application is available at `http://127.0.0.1:8080`; the hot-reload frontend is at `http://127.0.0.1:5174`. Both ports remain loopback-only. Normal shutdown preserves the named H2 volume:

```bash
docker compose down
```

After configuration or image changes, use `docker compose up --build --detach` again. Inspect status and logs with:

```bash
docker compose ps
docker compose logs --tail 200 app
docker compose logs --tail 200 frontend
```

Do not paste logs into public issues until they have been checked for customer data, tokens, URLs, and environment values.

## Finance verification and evidence

Use the payment transaction endpoints, rather than the console CSV, when investigating a collection or refund:

```text
GET /api/payments/{paymentId}/transactions?limit=100&offset=0
GET /api/orders/{orderId}/payment-transactions?limit=100&offset=0
```

Transaction rows are append-only application audit records. They are stored in the same H2 database as payment snapshots, request hashes, and exact idempotency replay responses, so the stopped-database backup procedure below must preserve and protect them together. Ledger responses expose `actor`, but `LOCAL_OPERATOR`, shared `zippy-api-key`, `ZIPPY_SYSTEM`, and `MIGRATION` identify a channel or process rather than an individual human; `LEGACY_UNKNOWN` means a pre-V9 operation had no safely reconstructable actor. The rows are not cryptographically signed, independently reconciled, or evidence that a real provider moved funds.

Report dates are inclusive UTC `YYYY-MM-DD` inputs. Record the returned `filters.fromInclusive` and next-day `filters.toExclusive` with any incident evidence. Cash-flow totals and daily trends use transaction event time; the payment list and current-state balances use payment creation time; shipment metrics use shipment creation time. Summary `scope` labels these bases explicitly and declares that top-level operational totals are `ALL_TIME` while finance is `FILTERED`. Check that `netCollected = grossCollected - refunds`, but do not treat `shipmentCost` or `totalShippingCost` as revenue.

Search is case-insensitive and literal; `%`, `_`, and `!` do not act as SQL pattern or escape operators. The reports screen publishes a new summary and shown-row page only when both requests for one refresh succeed, so an error leaves the prior coherent pair visible.

The browser's "Export shown rows" CSV contains only the currently visible filtered page, at most 50 rows. It uses fixed two-decimal Amount, Refunded amount, and status-aware Net cash collected columns and includes both capture and refund reconciliation references. It is escaped and spreadsheet-formula-neutralized, but it is neither a complete export nor the immutable ledger. Preserve paginated JSON ledger responses and their request filters for an audit investigation.

V8 created state snapshots for pre-existing payments. V9 leaves that applied migration unchanged, assigns migration actors, and adds missing inferred `CAPTURED`, `COD_COLLECTED`, and `REFUNDED` cash rows. Those cash-event reasons explicitly say they were inferred, and their timestamps come from legacy payment creation/update fields rather than provider events. Retain that qualification in exports and reconciliation notes; do not present reconstructed rows as independently verified settlement history.

Never enter real provider credentials, bank references, card data, or customer financial data into this demo. Production use remains blocked until an approved provider, independent settlement reconciliation, access control, retention, and tamper-evident audit controls exist.

## Backup the Compose database

H2 files must not be copied while the application is writing to them. This procedure intentionally stops the stack to create a consistent local-demo backup.

1. Create a timestamped directory outside the repository when possible. The repository-local `backups/` directory is ignored only as a convenience.
2. Stop both services without removing their containers or volume.
3. Copy the complete `/app/data` directory, not just one H2 file.
4. Record the Git commit, effective non-secret configuration, Flyway migration version, UTC time, and SHA-256 checksums beside the backup. Include a read-only finance summary and the applicable report filter boundaries when the backup is part of a financial investigation.
5. Restart the application, wait for health, and then restart the frontend.

Example, after replacing `<backup-directory>` with an existing directory:

```bash
docker compose stop frontend app
docker compose cp app:/app/data/. <backup-directory>/data
docker compose start app
curl --retry 20 --retry-delay 2 --retry-connrefused --fail http://127.0.0.1:8080/api/health
docker compose start frontend
```

Encrypt backups at rest, restrict access, copy them to a separate failure domain, and test restoration on a schedule. Define recovery point and recovery time objectives before relying on the service. Production databases must use supported snapshot, replication, and point-in-time recovery features rather than this file-copy procedure.

## Restore the Compose database

Restoration replaces current demo data. Confirm the target project and backup path, take a fresh safety backup when possible, and verify checksums before proceeding.

```bash
docker compose stop frontend app
docker compose run --rm --no-deps --user root --entrypoint sh app -c "find /app/data -mindepth 1 -maxdepth 1 -delete"
docker compose cp <backup-directory>/data/. app:/app/data
docker compose run --rm --no-deps --user root --entrypoint sh app -c "chown -R --reference=/app /app/data"
docker compose start app
curl --retry 20 --retry-delay 2 --retry-connrefused --fail http://127.0.0.1:8080/api/health
docker compose start frontend
```

Verify a known order, shipment, and report after health succeeds. If Flyway rejects the restored schema, stop and investigate the application/backup migration boundary; never alter an already applied migration to force startup.

To intentionally discard all local Compose data instead of restoring it:

```bash
docker compose down --volumes
docker compose up --build --detach
```

`--volumes` is destructive and cannot be undone without an independent backup.

## Failure triage and recovery

1. Confirm the failing surface: UI, API, database readiness, or carrier simulation.
2. Run `docker compose ps` and inspect the health state and recent service logs.
3. Check port conflicts, available disk space, file permissions, and the Java/Node versions.
4. Validate Compose with `docker compose config --quiet`; do not publish rendered secret values.
5. Restart only the affected service with `docker compose restart app` or `docker compose restart frontend`.
6. After an application restart, require `/api/health` to return HTTP 200 with `status: UP`, then verify one read-only API workflow.

Common cases:

- Missing local frontend packages: rerun `npm run dev`; the launcher performs `npm ci` when its required packages are absent.
- Port already in use: stop the conflicting process. The configured ports are strict and do not silently move.
- H2 lock or corruption symptoms: stop duplicate application processes. Do not delete files; preserve evidence and restore the latest verified backup if required.
- Flyway validation or checksum failure: stop all application processes and back up the database before investigating. Compare the application commit, migration source, and recorded Flyway history. Never edit or `repair` an already-applied migration merely to accept a changed checksum. Restore the checksum-matching historical migration and move new work to a higher-versioned migration, or restore the compatible application and database together. Delete local H2 files only for an intentional disposable-data reset.
- Repeated unhealthy restarts: stop automatic retries, capture sanitized logs and resource state, and escalate before making destructive changes.

## Incident and rollback notes

For a suspected secret or vulnerability, follow [SECURITY.md](../SECURITY.md). Revoke exposed credentials before attempting history cleanup. Preserve sanitized timestamps, request IDs, image/source versions, and actions taken.

Application rollback uses a previously verified source commit or immutable image plus its matching configuration. Database rollback is a separate recovery operation: Flyway is forward-only, and newer migrations can make an older application unsafe or incompatible. Make the rollback decision against the recorded migration boundary and restore point, then repeat health and workflow verification.
