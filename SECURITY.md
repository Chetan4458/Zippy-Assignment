# Security Policy

## Supported versions

This repository is a demonstration application and does not publish supported release branches. Security fixes are applied only to the current default branch. Older commits, forks, exported archives, and locally modified copies are not supported.

## Reporting a vulnerability

Do not open a public issue for a suspected vulnerability or include credentials, personal data, database files, or exploit payloads in a public channel.

Use GitHub's private vulnerability reporting for this repository when it is enabled. Otherwise, contact the repository owner privately through the same channel that provided access to the project and ask for a secure reporting path. Include:

- the affected commit and component;
- prerequisites and minimal reproduction steps;
- likely impact and whether exploitation was observed;
- suggested mitigations, if known; and
- a safe way to contact you for follow-up.

Maintainers should acknowledge reports privately, reproduce and classify the issue, prepare a fix and regression test, and coordinate disclosure. This demo has no contractual response-time commitment.

## Scope and demo boundary

Credential exposure, injection, cross-tenant access, unsafe webhook handling, payment-state bypasses, dependency compromise, and accidental network exposure are in scope. The loopback-only `local` profile deliberately disables authentication and webhook signing. The `prod` profile instead fails closed, requires an operational API key and carrier-specific HMAC secrets, and denies developer/mock mutation routes. This is service-level protection, not user identity, role authorization, or tenant isolation. Any deployment exposed to other users or networks must complete the controls in [the production-readiness guide](docs/PRODUCTION_READINESS.md).

Use only synthetic data while testing. Do not disrupt systems you do not own, attempt denial of service, or retain data obtained during research.

## Exposed-secret response

Deleting a file in a later commit does not remove it from Git history. If a credential, token, diagnostic bundle, or environment dump is committed:

1. Revoke or rotate every affected credential immediately.
2. Preserve only non-sensitive incident evidence in an access-controlled location.
3. Determine where the material was cloned, cached, published, or used.
4. Purge it from repository history using an agreed history-rewrite procedure.
5. Invalidate old clones and cached artifacts, then verify with a full-history secret scan.
6. Document the cause and add a preventive control without recording the secret itself.

Treat rotation as the primary containment step; history rewriting alone cannot make an exposed credential safe again.
