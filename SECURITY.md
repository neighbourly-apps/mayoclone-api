# Security Policy — mayoclone-api

MayoClone is a multi-tenant IRCTC e-catering order aggregator. Vendors connect a
mail source; MayoClone ingests aggregator order emails and surfaces them in a
per-tenant dashboard. This document describes the security model of the API and
how to report vulnerabilities.

- Architecture: [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)
- Ingestion trust models + setup: [`docs/INGESTION.md`](docs/INGESTION.md)
- Deploy / secrets / rotation / incidents: [`docs/RUNBOOK.md`](docs/RUNBOOK.md)

## Reporting a vulnerability

Please report suspected vulnerabilities privately — do **not** open a public
issue.

- Email: **security@mayoclone.example** (replace with your real security contact
  before go-live).
- Include: affected endpoint/component, reproduction steps, impact, and any PoC.
- We aim to acknowledge within **2 business days** and to provide a remediation
  timeline after triage. Please allow reasonable time to remediate before any
  public disclosure.

Do not run automated scanners against shared/production environments without
prior written permission.

---

## Authentication

| Aspect | Implementation |
|--------|----------------|
| Credential | Email + password. Passwords hashed with **Argon2id** (`Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8()`). |
| Access token | **JWT, HS256**, signed with `MAYOCLONE_JWT_SECRET`. TTL **15 min** (`MAYOCLONE_JWT_ACCESS_TTL`). Claims: `sub` (accountId), `email`, `role`. Sent as `Authorization: Bearer <token>`. |
| Refresh token | **Opaque, high-entropy random**, TTL **7 days** (`MAYOCLONE_JWT_REFRESH_TTL`). Only a **SHA-256 hash** is stored in the DB. Rotated on every use; **reuse of an already-rotated token revokes the whole token family** (theft/replay signal), forcing re-login. |
| Refresh transport | **httpOnly, Secure, SameSite=Lax** cookie scoped to path `/api/auth`. JavaScript cannot read it. `Secure` is toggled by `MAYOCLONE_COOKIE_SECURE` (must be `true` in prod over HTTPS). |
| CSRF | **Double-submit token** on the cookie-bearing routes (`/api/auth/refresh`, `/api/auth/logout`): the non-httpOnly `csrf` cookie value must equal the `X-CSRF-Token` request header (`DoubleSubmitCsrfFilter`). Spring's stateful CSRF is disabled because the API is otherwise Bearer/stateless. |
| Session | **Stateless** (`SessionCreationPolicy.STATELESS`); no server-side HTTP session. |

## Authorization & tenancy

- The tenant is an **`Account`**. Every domain row (vendor, order, ingest
  failure, …) is scoped by `accountId`, which is derived **only** from the
  authenticated JWT — never from a request body or query param.
- **Cross-tenant access returns `404`** (not `403`), so the API does not reveal
  that another tenant's resource exists.
- Roles: **OWNER**, **ADMIN**. Aggregator **catalog writes** (POST/PUT/DELETE
  `/api/aggregators`) require **ADMIN** (`@EnableMethodSecurity` + route matchers
  in `SecurityConfig`). Reads require any authenticated user.
- Public routes (no auth): `POST /api/auth/{register,login,refresh,logout}`,
  `/api/inbound/**` (HMAC-guarded), `/api/demo/**`, `GET /api/mail/gmail/callback`
  (signed-state validated in-handler), and `/actuator/health`, `/actuator/info`.
  Everything else under `/api/**` requires a valid Bearer token; anything
  unmatched is `denyAll`.

## Rate limiting

In-memory per-IP token buckets (Bucket4j, `RateLimitFilter`). On exhaustion:
HTTP **429** with a `Retry-After` header.

| Scope | Limit |
|-------|-------|
| `POST /api/auth/login`, `POST /api/auth/register` | **10 / min / IP** |
| All endpoints (global) | **300 / min / IP** |

Client IP is taken from the first `X-Forwarded-For` entry when present, else the
socket address. Buckets are per-JVM; a multi-instance deployment should front
this with a shared limiter (e.g. Redis) or an edge/WAF rate limiter.

## Security headers

Set on all responses by `SecurityConfig`:

- `Strict-Transport-Security: max-age=31536000; includeSubDomains`
- `X-Content-Type-Options: nosniff`
- `X-Frame-Options: DENY`
- `Referrer-Policy: no-referrer`
- `Content-Security-Policy: default-src 'none'; frame-ancestors 'none'; base-uri 'none'`
  (restrictive — this is a JSON API, not an HTML app)

**CORS**: allowlist from `MAYOCLONE_CORS_ORIGINS` (comma-separated), credentials
enabled, methods `GET/POST/PUT/DELETE/OPTIONS`, allowed headers
`Authorization, Content-Type, X-CSRF-Token`. Wildcard origins are incompatible
with credentialed CORS and must not be used.

## Secret handling & rotation

| Secret | Purpose | At rest |
|--------|---------|---------|
| `MAYOCLONE_JWT_SECRET` | Signs/verifies access JWTs | Env var only; never persisted |
| `MAYOCLONE_ENC_KEY` | AES-256-GCM key for column encryption | Env var only; never persisted |
| IMAP passwords | Mailbox access (IMAP path) | **AES-256-GCM encrypted** column (`AesGcmStringConverter`) |
| Gmail OAuth refresh tokens | Gmail pull (OAuth path) | **AES-256-GCM encrypted** column (`AesGcmStringConverter`) |
| Refresh tokens | Session continuity | **SHA-256 hash only** |
| DB / provider / OAuth secrets | Infra | Env vars / secret manager |

- Column encryption uses a fresh random 12-byte IV per value; stored form is
  `base64(IV || ciphertext||GCM-tag)`. `MAYOCLONE_ENC_KEY` must base64-decode to
  exactly 32 bytes.
- **Never** commit real secrets. `.env.example` holds placeholders only.
- Rotation procedures (JWT secret, encryption key with re-encrypt, inbound
  signing secret, DB creds) are in [`docs/RUNBOOK.md`](docs/RUNBOOK.md).

## Ingestion trust models

MayoClone supports three ways for a vendor's aggregator email to reach it, in
descending order of trust. Each is documented with a setup guide and threat
notes in [`docs/INGESTION.md`](docs/INGESTION.md):

1. **Forwarding webhook (PRIMARY)** — provider POSTs to `POST /api/inbound/email`
   signed **HMAC-SHA256** (`X-Mayo-Signature: t=..,v1=..`, secret
   `MAYOCLONE_INBOUND_SIGNING_SECRET`, ±5 min replay window). **No mailbox
   credentials stored.**
2. **Gmail OAuth** — one-click connect, `gmail.readonly` scope, signed-state
   CSRF, encrypted refresh token. Requires Google verification + CASA assessment
   at scale.
3. **IMAP app-password (fallback)** — vendor-supplied IMAP creds (app-specific
   password), AES-GCM encrypted, `imaps` with server-identity check.

## Reliability & auditability

- **Dead-letter queue**: unroutable/unparseable mail is written to
  `ingest_failure` (tenant-scoped review queue: list / retry / delete) instead
  of being silently dropped.
- **Dedup**: on `sourceMessageId`, and on the unique
  `(aggregator_id, external_order_id)` constraint.
- **Immutable audit log** (`audit_log`): append-only, enforced by a PostgreSQL
  trigger that **raises on any UPDATE or DELETE**. Recorded actions include:
  register, login (+failure), logout, refresh-token reuse, vendor create/delete,
  Gmail connect, aggregator create/update/delete, and inbound accept/reject.

---

## Threat model (STRIDE)

Mitigations below are **already implemented** unless the residual-risk column
says otherwise.

### Spoofing

| Threat | Mitigation | Residual risk |
|--------|-----------|---------------|
| Forged inbound webhook (attacker POSTs fake orders to `/api/inbound/email`) | HMAC-SHA256 signature required (`X-Mayo-Signature`), verified constant-time against `MAYOCLONE_INBOUND_SIGNING_SECRET`; bad/missing signature → 401 | Secret compromise ⇒ forgery. Rotate on suspicion; keep secret out of logs/VCS. |
| Email spoofing (attacker forges an aggregator's `From` into the ingestion path) | Forwarding path only accepts signed provider webhooks for a **known** per-vendor `ingestAddress`; unknown recipient → 202 with nothing stored. Routing keys off aggregator `senderDomains`. | A vendor forwarding attacker-crafted mail to their own ingest address can inject bogus orders **into their own tenant only**. SPF/DKIM/DMARC checks are the provider's responsibility; enable them at the inbound provider. |
| Impersonating another user | Argon2id password verification; stateless JWT signed with server secret; no client-supplied identity | Phishing / credential stuffing. Mitigated by login rate limit (10/min/IP) + audit of login failures. |

### Tampering

| Threat | Mitigation | Residual risk |
|--------|-----------|---------------|
| Tampering with a JWT to change account/role | HS256 signature verified on every request; unsigned/altered tokens rejected | Signing-secret compromise ⇒ forgeable tokens. Rotate `MAYOCLONE_JWT_SECRET`. |
| Replaying a captured webhook | Timestamp in signature; **±5 min skew window**, HMAC binds `t.body` | Replay possible **within** the 5-min window if TLS is broken. Enforce HTTPS end-to-end. |
| OAuth `state` tampering / injection (CSRF on the Gmail callback) | `state` is **signed** (`OAuthStateSigner`) and binds the initiating `accountId`; tampered/expired state → 400 | Signing key is the JWT/enc infrastructure; same rotation applies. |
| Modifying data at rest | Hibernate `ddl-auto=validate`; Flyway-owned schema; audit log UPDATE/DELETE blocked by DB trigger | DB-admin/root compromise. Restrict DB privileges; back up. |

### Repudiation

| Threat | Mitigation | Residual risk |
|--------|-----------|---------------|
| User denies performing a sensitive action | **Immutable `audit_log`** (append-only, DB-trigger-enforced) records auth, vendor, aggregator, Gmail-connect and inbound events with actor + IP + user-agent | Audit captures app-layer events only; correlate with infra logs. Ship audit rows off-host for tamper-evidence. |
| Correlating a request across logs | `X-Request-Id` correlation id propagated into every log line (MDC `requestId`); JSON logs on `prod`/`json` profile | — |

### Information disclosure

| Threat | Mitigation | Residual risk |
|--------|-----------|---------------|
| Cross-tenant data read | All queries scoped by JWT `accountId`; cross-tenant lookups return **404** (existence not revealed) | Application-layer scoping — covered by `OrderServiceTenantScopingTest`. Keep new queries scoped. |
| Secret exposure (IMAP pwd / OAuth token) | AES-256-GCM at rest; never returned in any DTO or `toString()`; refresh tokens stored as SHA-256 hash only | `MAYOCLONE_ENC_KEY` compromise ⇒ decryptable. Use a secret manager; rotate + re-encrypt. |
| Leaking which ingest addresses exist | Unknown inbound recipient → **202 "ignored"**, nothing stored/echoed | — |
| Verbose errors / stack traces | Stateless API returns minimal error bodies; encryption failures never include plaintext | Audit that new endpoints don't leak internals. |
| Token theft via XSS | Access token is short-lived and held **in memory** by the SPA; refresh token is **httpOnly** (JS cannot read it) | XSS could still call the API as the user while the tab is open. Web CSP served by nginx; see the web repo `SECURITY.md`. |

### Denial of service

| Threat | Mitigation | Residual risk |
|--------|-----------|---------------|
| Brute-force / credential stuffing on login | **10/min/IP** auth rate limit → 429 | Distributed IPs bypass per-IP caps; add an edge/WAF limiter. |
| Request flooding | **300/min/IP** global rate limit → 429 | Per-JVM buckets; use a shared limiter across replicas. |
| Malformed / huge webhook payloads | Signature checked before parse; parse failures dead-letter (not crash) | Very large bodies — cap request size at the edge/provider. |
| Poison mail that never parses | Dead-lettered to `ingest_failure` review queue; pipeline continues | Queue growth — monitor `mayoclone.ingest.failures`. |

### Elevation of privilege

| Threat | Mitigation | Residual risk |
|--------|-----------|---------------|
| Non-admin performs aggregator catalog writes | Route matchers require role **ADMIN** for POST/PUT/DELETE `/api/aggregators` | Role assignment integrity — audited on aggregator changes. |
| Escaping tenant scope via crafted IDs | `accountId` never taken from client; server-side scoping + 404 | — |
| Reaching internal actuator endpoints | Only `health`/`info`/`metrics`/`prometheus` exposed; only `health`/`info` public | Protect `/actuator/prometheus` + `/metrics` at the network layer (scrape from inside the cluster). |
