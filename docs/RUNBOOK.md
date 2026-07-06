# Operations Runbook — mayoclone-api

Deploy, configure, and operate the API in production. Pair with
[`../SECURITY.md`](../SECURITY.md) and [`INGESTION.md`](INGESTION.md).

Stack: Spring Boot 3.3.6 / Java 21, PostgreSQL + Flyway, Micrometer/Prometheus.
Config is env-driven; see [`../.env.example`](../.env.example) for the full list.

---

## 1. Required environment & secrets

Set these before starting in prod. **`[REQ]`** must be a strong, non-default value.

| Var | Req | Purpose |
|-----|-----|---------|
| `MAYOCLONE_JWT_SECRET` | `[REQ]` | HS256 signing key for access JWTs |
| `MAYOCLONE_ENC_KEY` | `[REQ]` | AES-256-GCM key (base64, exactly 32 bytes) for column encryption |
| `MAYOCLONE_DB_URL` / `_USER` / `_PASSWORD` | `[REQ]` | Postgres connection |
| `MAYOCLONE_CORS_ORIGINS` | `[REQ]` | Allowlisted SPA origins (no wildcard) |
| `MAYOCLONE_COOKIE_SECURE` | `[REQ]` | `true` in prod (HTTPS) |
| `MAYOCLONE_FRONTEND_BASE_URL` | rec | SPA base URL for Gmail redirect |
| `MAYOCLONE_INBOUND_DOMAIN` | webhook | Per-vendor forwarding domain (needs MX) |
| `MAYOCLONE_INBOUND_SIGNING_SECRET` | webhook | HMAC secret for `/api/inbound/email` |
| `GOOGLE_OAUTH_CLIENT_ID` / `_CLIENT_SECRET` / `_REDIRECT_URI` | gmail | Enable Gmail OAuth (all three) |
| `MAYOCLONE_JWT_ISSUER` / `_ACCESS_TTL` / `_REFRESH_TTL` | opt | Token issuer + lifetimes |
| `MAYOCLONE_TRACING_SAMPLING` / `MAYOCLONE_OTLP_ENDPOINT` | opt | OTLP tracing (default off) |
| `SPRING_PROFILES_ACTIVE` | rec | `prod` → JSON logs + `ddl-auto=validate` |

If `MAYOCLONE_JWT_SECRET`, `MAYOCLONE_ENC_KEY` or
`MAYOCLONE_INBOUND_SIGNING_SECRET` are left unset, the app boots with **insecure
dev defaults** (logged) — never acceptable in prod.

### Generating secrets

```bash
# JWT signing secret (32+ bytes of entropy)
openssl rand -base64 48

# AES-256 encryption key — MUST base64-decode to exactly 32 bytes
openssl rand -base64 32

# Inbound webhook signing secret (set identically on the mail provider)
openssl rand -base64 32
```

Store secrets in a secret manager (AWS Secrets Manager / GCP Secret Manager /
Vault / k8s Secret) and inject as env vars. Never commit them.

---

## 2. Deploy

```bash
# Build the executable jar
./gradlew clean bootJar        # -> build/libs/mayoclone-api-*.jar

# Run (env vars supplied by your platform / secret manager)
SPRING_PROFILES_ACTIVE=prod java -jar build/libs/mayoclone-api-*.jar
```

- Serve behind a TLS-terminating reverse proxy / ingress. **HTTPS is mandatory**
  (Secure cookies, HSTS, webhook replay window all assume TLS). *(Operator to-do:
  provision TLS certificates.)*
- Set `X-Forwarded-For` at the proxy so rate limiting sees real client IPs.
- Expose only `/actuator/health` + `/actuator/info` publicly; scrape
  `/actuator/prometheus` and `/actuator/metrics` from inside the network only.
- Rate-limit buckets are per-JVM; with multiple replicas, add an edge/WAF limiter
  for a global cap.

---

## 3. Database & Flyway migrations

- **Flyway owns the schema.** On startup it applies `db/migration/V1..V4`
  (`V1__init` core+auth, `V2__mail_source`, `V3__ingest_failure`,
  `V4__audit_log`). `baseline-on-migrate=true`.
- Hibernate runs **`ddl-auto=validate`** — it never creates/alters tables; it only
  checks the entity mappings match the migrated schema. A validation failure at
  boot means a migration is missing or out of sync.
- Migrations run automatically on deploy. For zero-downtime, keep migrations
  backward-compatible with the previous app version (expand/contract).
- The `audit_log` table is protected by a DB trigger that **raises on UPDATE or
  DELETE** — do not try to mutate/clean it in place; archive by copying rows out.

---

## 4. Backups

- Back up PostgreSQL regularly (managed automated backups or `pg_dump`); test
  restores. This is the only durable store (orders, vendors, audit log, encrypted
  secrets).
- **Back up `MAYOCLONE_ENC_KEY` separately and securely.** Losing it makes all
  encrypted IMAP passwords and Gmail refresh tokens **unrecoverable** (vendors
  would have to reconnect). A DB backup without the key is useless for those
  columns — and a DB backup *with* the key co-located defeats encryption at rest.
- `MAYOCLONE_JWT_SECRET` loss only invalidates live access tokens (users
  re-authenticate) — no data loss.

---

## 5. Secret & key rotation

| Secret | Rotation procedure | Impact |
|--------|--------------------|--------|
| `MAYOCLONE_JWT_SECRET` | Replace env var, restart. | All live access tokens invalidated; clients silently refresh (refresh cookie still valid) or re-login. Low disruption. |
| `MAYOCLONE_INBOUND_SIGNING_SECRET` | Rotate on the mail **provider** first (or run both briefly), then update the API env, restart. | Mismatched webhooks → 401 until both sides agree. Coordinate. |
| DB credentials | Rotate in Postgres + `MAYOCLONE_DB_*`, restart. | Brief connection blip. |
| `GOOGLE_OAUTH_CLIENT_SECRET` | Rotate in Google Cloud Console + env, restart. | New Gmail connects use the new secret; existing stored refresh tokens keep working. |
| `MAYOCLONE_ENC_KEY` (AES) | **Re-encrypt required.** No in-place rotation exists. Procedure below. | Get wrong → all encrypted columns undecryptable. |

**Encryption-key rotation (re-encrypt):** the converter uses a single key with no
key-id envelope, so you must decrypt-with-old / re-encrypt-with-new out of band:
1. With the **old** key live, export the plaintext of `vendor.imap_password` and
   `vendor.oauth_refresh_token` (e.g. a one-off maintenance task using the current
   converter).
2. Set the **new** `MAYOCLONE_ENC_KEY`, restart with the plaintext re-persisted so
   the new key re-encrypts on write.
3. Simplest safe fallback: have vendors **re-enter IMAP passwords / re-connect
   Gmail** after the key change (this naturally re-encrypts under the new key).

---

## 6. Health, metrics & logs

| Endpoint | Purpose | Exposure |
|----------|---------|----------|
| `GET /actuator/health` | Liveness/readiness (probes enabled) | Public |
| `GET /actuator/info` | Build/info | Public |
| `GET /actuator/metrics` | Micrometer metrics | Internal only |
| `GET /actuator/prometheus` | Prometheus scrape | Internal only |

**Key metrics** (scrape `/actuator/prometheus`):

| Metric | Tags | Watch for |
|--------|------|-----------|
| `mayoclone.orders.ingested` | `aggregator`, `sourceType` | Sudden drop = ingestion stalled |
| `mayoclone.ingest.failures` | `reason` | Spike = aggregator format change / routing gap |
| `mayoclone.webhook.inbound` | `result` (accepted/unmatched/rejected_signature/rejected_payload) | `rejected_signature` spike = secret mismatch/attack |
| `mayoclone.auth.login` | `result` | Failure spike = brute force |
| `mayoclone.sync.duration` | `sourceType` | Latency of IMAP/Gmail pulls |

**Logs**: `prod`/`json` profile emits structured JSON (logstash encoder);
otherwise human-readable console. Every line carries the `requestId` MDC value,
sourced from the inbound `X-Request-Id` header (minted if absent) by
`CorrelationIdFilter` — use it to trace a request end-to-end.

**Audit log**: query `audit_log` (append-only) for who-did-what: register, login
(+failure), logout, refresh-token reuse, vendor create/delete, Gmail connect,
aggregator create/update/delete, inbound accept/reject.

---

## 7. Common incidents

### Refresh-token reuse lockout ("logged out unexpectedly")
- **Symptom**: a user's session dies; `auth.refresh.reuse` audit event + a
  `Refresh token reuse detected` warning; whole token **family revoked**.
- **Cause**: a rotated (already-used) refresh token was presented again — real
  theft/replay, **or** a client bug replaying an old token (e.g. two tabs racing,
  a stale cached token).
- **Action**: this is by design (theft response). Have the user **log in again**.
  If it recurs for many users, investigate the client's single-flight refresh
  logic. Check `audit_log` for the affected `familyId`/account.

### Webhook returning 401 (`rejected_signature`)
- **Cause**: `MAYOCLONE_INBOUND_SIGNING_SECRET` mismatch between API and provider;
  clock skew > ±5 min; provider not sending `X-Mayo-Signature`; body altered in
  transit.
- **Action**: confirm the secret matches on both sides; verify server clock (NTP);
  confirm the provider signs `"<t>.<rawBody>"` with HMAC-SHA256 and sends
  `t=..,v1=..`. A 202 (not 401) means signature OK but recipient unknown — check
  the vendor's `ingestAddress`.

### IMAP auth failures
- **Cause**: vendor rotated/revoked their app password; 2FA/app-password not set
  up; provider blocked the login; wrong host/port; TLS/identity mismatch.
- **Action**: have the vendor regenerate an **app-specific password** (plain
  passwords are rejected by providers) and re-save the vendor. Verify host/port
  (`imap.gmail.com:993`, SSL on). Check `mayoclone.sync.duration` /
  `ingest.failures`. Consider migrating them to the forwarding webhook.

### Gmail connect returns 503 / fails verification
- **503**: one of `GOOGLE_OAUTH_CLIENT_ID/CLIENT_SECRET/REDIRECT_URI` is unset —
  Gmail is disabled. Set all three.
- **"App not verified" / limited to test users**: expected until Google OAuth
  verification + **CASA** is completed for `gmail.readonly`. See
  [`INGESTION.md`](INGESTION.md). Add the user as a Test User meanwhile.

### Database down / migration failure at boot
- **DB down**: health goes unready; the app can't serve. Restore connectivity /
  failover; check `MAYOCLONE_DB_*` and network.
- **Flyway/validate failure at boot**: a migration didn't apply or the schema
  drifted from the entities. Do **not** flip `ddl-auto` to `update` in prod —
  fix/add the migration. Check `flyway_schema_history`.

### Rate-limited (429) reports
- Expected under `>300/min/IP` global or `>10/min/IP` on login/register. If
  legitimate traffic trips it, confirm `X-Forwarded-For` is set correctly at the
  proxy (so many users aren't collapsed to one IP) and consider an edge limiter.
