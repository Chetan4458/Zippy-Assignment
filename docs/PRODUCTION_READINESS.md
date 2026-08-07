# Production Readiness

## Current decision: no-go

Zippy is intentionally a local, single-tenant demonstration. It is not production-ready and must not be exposed directly to an untrusted network. Loopback port bindings, mock carriers, embedded H2, and local default credentials are conveniences for development, not production controls.

At minimum, production deployment requires all of the following work to be complete, reviewed, and assigned to named owners.

## Implemented baseline

The container defaults to a fail-closed `prod` profile. That profile requires a strong operational API key, carrier-specific HMAC webhook secrets, signed timestamps and event identifiers, configurable CORS origins, and an explicit datasource; developer and mock mutation routes are denied. Health, metrics, dependency audit, SBOM generation, full-history secret scanning, and authenticated container smoke checks are automated. These controls reduce accidental exposure but do not satisfy the remaining identity, tenancy, database, availability, and compliance requirements below.

## Required controls

### Identity and application security

- Replace the single operational key with managed human/service identity, authorize by merchant/tenant and role, and test cross-tenant isolation.
- Replace shared or unavailable ledger actors (`zippy-api-key`, `LOCAL_OPERATOR`, and `LEGACY_UNKNOWN`) with immutable individual or workload identities and enforce maker-checker approval where finance policy requires it.
- Keep developer flags and mock-carrier mutation routes unavailable in every deployed environment.
- Integrate each carrier's real signature contract, managed key rotation, timestamp policy, and durable replay identifiers.
- Integrate a compliant payment provider; never process real payment credentials in this application. Provider/reference fields in the demo are labels and do not prove authorization or settlement.
- Reconcile captured, collected, refunded, failed, and voided events independently against signed provider settlement data.
- Protect the append-only application transaction ledger with least-privilege access, retention, tamper-evident storage, and audited export procedures.
- Define retention and access controls for stored idempotency request hashes and exact replay responses; they are operational data inside the database, not public ledger fields.
- Add request limits, rate limits, TLS termination, security headers, restrictive production CORS, and abuse monitoring.
- Obtain a threat model and security review covering order, shipment, webhook, and payment state transitions.

### Data and privacy

- Replace embedded H2 with a supported production database and document sizing, encryption, retention, and deletion policies.
- Classify customer fields, minimize collected data, and define regional/privacy obligations before ingesting real records.
- Store secrets in a managed secret store with rotation, access logging, and least-privilege access.
- Define recovery point and recovery time objectives, automate encrypted backups, and successfully rehearse restoration.
- Demonstrate that backup and point-in-time recovery preserve payment snapshots and their transaction ledger consistently.
- Reconcile V9 `MIGRATION` cash rows separately: their event reasons and timestamps are explicitly inferred from legacy snapshots and cannot establish provider settlement. Treat the V8 state snapshots as migrated state evidence, not cash-event or provider-settlement evidence.
- Treat Flyway migrations as forward-only release artifacts; review compatibility and rollback implications before deployment.

### Reliability and operations

- Define service-level objectives, alerts, dashboards, structured logs, traces, and an on-call escalation path.
- Add capacity and load tests for concurrent idempotent order creation, carrier delays, webhook bursts, and database contention.
- Set CPU, memory, disk, connection-pool, timeout, and retry budgets; test graceful termination and restart behavior.
- Design high availability, database failover, queueing/retry behavior, and dependency outage handling.
- Maintain an incident response process and exercise the [operations runbook](OPERATIONS_RUNBOOK.md).

### Release and supply chain

- Require reviewed pull requests and passing CI on protected branches.
- Review Dependabot changes, npm audit findings, secret-scan results, and CycloneDX SBOMs for each candidate.
- Publish immutable, traceable images through an approved registry and establish signing, provenance, and vulnerability scanning.
- Record source commit, image identifier, configuration version, migration set, SBOM, approver, and deployment time.
- Define finance-report ownership and document UTC date boundaries, metric definitions, pagination, reconciliation exceptions, and evidence retention. A client-side CSV of shown rows is not an authoritative export.
- Verify atomic report-refresh behavior and stale-data signaling, but do not substitute UI consistency for an independently generated finance export.
- Rehearse application rollback and database recovery separately; a database migration can make an older application incompatible.

## Environment and configuration policy

Use separate accounts, networks, databases, domains, keys, and observability destinations for development, staging, and production. Production values must come from the deployment platform or secret manager, never `.env`, source files, image layers, CI logs, or command history. Validate required values at startup and fail closed when they are absent.

The checked-in `.env.example` is only a local Compose template. The Compose defaults (`zippy-local`, automated mock carriers, and loopback ports) must not be promoted to another environment.

## Release gate

A release candidate is eligible for production only when:

1. Every required control above is implemented or has a time-bounded, explicitly accepted exception.
2. `npm ci`, `npm run check`, Compose validation, security scans, SBOM generation, and container smoke tests pass from a clean checkout.
3. Schema changes have been tested against a production-like database copy containing synthetic or properly protected data.
4. Backup restoration and application rollback have been rehearsed for this migration boundary.
5. Monitoring, alert routing, support ownership, change window, and rollback authority are confirmed.
6. A named release approver records a go decision.

Any unresolved authentication, tenant-isolation, secret-management, backup-restore, critical vulnerability, or migration-compatibility issue is an automatic no-go.

## Rollback principles

Keep the last known-good application artifact and its configuration available. Prefer a forward application fix when a migration is not backward compatible. Never edit or remove an already applied Flyway migration. Before a risky schema change, take a verified database backup and document the exact restore point. See [the operations runbook](OPERATIONS_RUNBOOK.md) for the local H2 procedure; production databases require their platform-native snapshot and point-in-time recovery process.

Security concerns should be handled according to [SECURITY.md](../SECURITY.md).
