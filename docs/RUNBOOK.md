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
| `MAYOCLONE_GMAIL_PUBSUB_TOPIC` | gmail push | Pub/Sub topic for `users.watch`; blank → poll fallback |
| `MAYOCLONE_GMAIL_PUSH_AUDIENCE` | gmail push | OIDC audience required on the push endpoint (preferred) |
| `MAYOCLONE_GMAIL_PUSH_TOKEN` | gmail push | Shared-secret `?token=` fallback (used only if no audience) |
| `MAYOCLONE_GMAIL_WATCH_RENEW_MS` / `MAYOCLONE_POLL_SKIP_GMAIL_WHEN_PUSH` | opt | Watch renew cadence (6h) / skip Gmail in poll (true) |
| `MAYOCLONE_JWT_ISSUER` / `_ACCESS_TTL` / `_REFRESH_TTL` | opt | Token issuer + lifetimes |
| `MAYOCLONE_TRACING_SAMPLING` / `MAYOCLONE_OTLP_ENDPOINT` | opt | OTLP tracing (default off) |
| `SPRING_PROFILES_ACTIVE` | rec | `prod` → JSON logs + `ddl-auto=validate` |
| `MAYOCLONE_OTP_DEV_MODE` | `[REQ]` | `false` in prod (never leak the OTP `devCode`) |
| `RAZORPAY_KEY_ID` / `_KEY_SECRET` / `_WEBHOOK_SECRET` | billing | Set all three to flip checkout from 503 → live (see §8) |
| `SPRING_MAIL_HOST` / `_PORT` / `_USERNAME` / `_PASSWORD` | email | Set `HOST` to flip OTP + renewal emails from dev-log → real (see §9) |
| `MAYOCLONE_OTP_FROM` | email | From-address on OTP + renewal-reminder emails |
| `MAYOCLONE_BILLING_ENFORCE` / `_DEV_MODE` | billing | Gate on (`true`) / dev-activate off (`false`) in prod |

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

## 1a. Go-live env vars checklist

The single authoritative list to take an instance from "boots with insecure dev
defaults" to production. Group by concern; set every var in a group before
relying on that feature.

**Always required (app will not be safe without these):**

```bash
# Crypto secrets — generate fresh, store in a secret manager:
openssl rand -base64 48   # → MAYOCLONE_JWT_SECRET  (>= 32 bytes of entropy)
openssl rand -base64 32   # → MAYOCLONE_ENC_KEY     (MUST decode to exactly 32 bytes → AES-256)
openssl rand -base64 32   # → MAYOCLONE_INBOUND_SIGNING_SECRET (if using the forwarding webhook)
```

- `MAYOCLONE_JWT_SECRET` — HS256 JWT signing key.
- `MAYOCLONE_ENC_KEY` — AES-256-GCM column-encryption key (base64 → exactly 32 bytes).
- `MAYOCLONE_DB_URL` + `MAYOCLONE_DB_USER` + `MAYOCLONE_DB_PASSWORD` — Postgres.
- `MAYOCLONE_CORS_ORIGINS` — SPA origin allowlist (no wildcard with credentials).
- `MAYOCLONE_COOKIE_SECURE=true` — Secure flag on refresh/CSRF cookies (needs HTTPS).
- `MAYOCLONE_OTP_DEV_MODE=false` — never return the plaintext OTP in prod.
- `MAYOCLONE_FRONTEND_BASE_URL` — SPA base URL (Gmail OAuth success redirect).
- `SPRING_PROFILES_ACTIVE=prod` — JSON logs + `ddl-auto=validate`.

**Billing (Razorpay) — enables paid checkout; see §8:**

- `RAZORPAY_KEY_ID`, `RAZORPAY_KEY_SECRET`, `RAZORPAY_WEBHOOK_SECRET` — set all three.
- `MAYOCLONE_BILLING_ENFORCE=true`, `MAYOCLONE_BILLING_DEV_MODE=false`.
- Optional plan overrides (defaults ₹1200/mo, ₹12999/yr, amounts in PAISE):
  `MAYOCLONE_BILLING_DEFAULT_PLAN` (`pro-monthly`|`pro-annual`),
  `MAYOCLONE_PLAN_MONTHLY_AMOUNT` (default `120000`),
  `MAYOCLONE_PLAN_ANNUAL_AMOUNT` (default `1299900`),
  and the matching `_NAME` / `_CURRENCY` / `_PERIOD_DAYS`.

**Email (SMTP) — enables real OTP + renewal-reminder emails; see §9:**

- `SPRING_MAIL_HOST` (the switch), `SPRING_MAIL_PORT`, `SPRING_MAIL_USERNAME`,
  `SPRING_MAIL_PASSWORD`,
  `SPRING_MAIL_PROPERTIES_MAIL_SMTP_AUTH=true`,
  `SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_ENABLE=true`,
  `MAYOCLONE_OTP_FROM`.

**Ingestion — pick at least one path (see [`INGESTION.md`](INGESTION.md)):**

- Forwarding webhook (primary): `MAYOCLONE_INBOUND_DOMAIN` (+ MX) and
  `MAYOCLONE_INBOUND_SIGNING_SECRET`.
- Gmail OAuth: `GOOGLE_OAUTH_CLIENT_ID` + `_CLIENT_SECRET` + `_REDIRECT_URI`
  (and, for push scale-out, `MAYOCLONE_GMAIL_PUBSUB_TOPIC` +
  `MAYOCLONE_GMAIL_PUSH_AUDIENCE`).
- IMAP polling: `MAYOCLONE_POLL_ENABLED=true` (per-vendor app passwords stored
  encrypted).

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

### Gmail push (Pub/Sub) ingestion
Scale-out path that replaces polling with Google Pub/Sub push + Gmail history
sync. Full design in [`INGESTION.md`](INGESTION.md) → "Gmail push (production
scale)". **One-time GCP setup:**
1. Create a Pub/Sub **topic** (e.g. `projects/PROJECT/topics/gmail-push`).
2. Grant `gmail-api-push@system.gserviceaccount.com` the **Pub/Sub Publisher**
   role on that topic (so Gmail may publish).
3. Create a **push subscription** → endpoint `https://<api>/api/inbound/gmail/push`,
   with **OIDC** enabled (service-account token; **audience** = your
   `MAYOCLONE_GMAIL_PUSH_AUDIENCE`, e.g. the endpoint URL).
4. Set env: `MAYOCLONE_GMAIL_PUBSUB_TOPIC`, `MAYOCLONE_GMAIL_PUSH_AUDIENCE`
   (preferred) **or** `MAYOCLONE_GMAIL_PUSH_TOKEN` (fallback shared secret, used
   via `?token=` on the push URL). Optional: `MAYOCLONE_GMAIL_WATCH_RENEW_MS`
   (default 6h), `MAYOCLONE_POLL_SKIP_GMAIL_WHEN_PUSH` (default true).
5. Reconnect each Gmail mailbox (or wait for the renewer) so `users.watch`
   registers against the topic. Watches auto-renew ~24h before their ~7-day expiry.

**Symptoms & fixes**
- **Push endpoint 503**: neither `MAYOCLONE_GMAIL_PUSH_AUDIENCE` nor
  `MAYOCLONE_GMAIL_PUSH_TOKEN` is set — the endpoint is disabled. Set one.
- **Push endpoint 401**: the OIDC token (or `?token=`) didn't verify. Check the
  subscription's OIDC **audience** matches `MAYOCLONE_GMAIL_PUSH_AUDIENCE`, and
  that the SA token isn't stripped by the proxy.
- **No orders arriving via push**: confirm the topic env var is set, the mailbox's
  `vendor.gmail_watch_expiration` is in the future (watch registered), and the
  `GMAIL_HISTORY` jobs are draining (not piling up in `ingest_job` as `DEAD`).
  Processing rides the durable job queue — see the job-queue metrics/incidents.
- **WARN "startHistoryId too old"**: normal after a long gap; the handler
  auto-rebaselines from the recent INBOX + profile historyId. No action needed.

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

---

## 8. Billing (Razorpay)

Subscription billing is **off until the three Razorpay secrets are set**. With
them all blank, `POST /api/billing/checkout` returns **503** and
verify/webhook reject (no secret to check against). Setting all three **flips
checkout live**.

**Env vars:**

| Var | Req | Purpose |
|-----|-----|---------|
| `RAZORPAY_KEY_ID` | `[REQ to go live]` | Order creation + payment-signature verify (Dashboard → Settings → API Keys). |
| `RAZORPAY_KEY_SECRET` | `[REQ to go live]` | Paired secret for the key id. |
| `RAZORPAY_WEBHOOK_SECRET` | `[REQ to go live]` | Verifies `POST /api/billing/webhook` (`X-Razorpay-Signature`) (Dashboard → Settings → Webhooks). |
| `MAYOCLONE_BILLING_ENFORCE` | rec | `true` → lapsed-trial/no-paid accounts get 402 on protected `/api/**`. |
| `MAYOCLONE_BILLING_DEV_MODE` | `[REQ]` | **`false` in prod** — `true` exposes `POST /api/billing/dev-activate` (fake payment). |
| `MAYOCLONE_BILLING_DEFAULT_PLAN` | opt | `pro-monthly` (default) or `pro-annual`; used when checkout omits a plan + as the trial default. |
| `MAYOCLONE_PLAN_MONTHLY_AMOUNT` | opt | Monthly price in **paise** (default `120000` = ₹1200.00). Also `_NAME` / `_CURRENCY` / `_PERIOD_DAYS`. |
| `MAYOCLONE_PLAN_ANNUAL_AMOUNT` | opt | Annual price in **paise** (default `1299900` = ₹12999.00). Also `_NAME` / `_CURRENCY` / `_PERIOD_DAYS`. |
| `MAYOCLONE_BILLING_REMINDER_ENABLED` | opt | Renewal-reminder sweep on/off (default on; needs SMTP — see §9). Also `_DAYS_BEFORE` (5), `_FIXED_DELAY_MS` (hourly), `_MAX_PER_RUN` (500). |

The plan **codes** are fixed (`pro-monthly`, `pro-annual`); only name/amount/
currency/period-days are configurable. **Amounts are in paise** (₹1 = 100 paise).

**Setup:** in the Razorpay Dashboard create API keys and a webhook pointed at
`https://<api>/api/billing/webhook`, copy the webhook signing secret, set the
three env vars, restart. Confirm `POST /api/billing/checkout` returns an order
(not 503). Keep `MAYOCLONE_BILLING_DEV_MODE=false` in prod.

---

## 9. Email (SMTP)

OTP and billing renewal-reminder emails **log to the console (dev sender) until a
real SMTP host is configured**. Setting **`SPRING_MAIL_HOST`** is the switch that
activates the real `SmtpOtpSender` and flips both OTP delivery and renewal
reminders from dev-logging to actual sending.

**Env vars** (standard Spring Boot mail properties):

| Var | Req | Purpose |
|-----|-----|---------|
| `SPRING_MAIL_HOST` | `[the switch]` | SMTP server host — set to enable real email. Blank → dev logger. |
| `SPRING_MAIL_PORT` | rec | Usually `587` (STARTTLS). |
| `SPRING_MAIL_USERNAME` | rec | SMTP username / API-key user. |
| `SPRING_MAIL_PASSWORD` | rec | SMTP password / API key (secret). |
| `SPRING_MAIL_PROPERTIES_MAIL_SMTP_AUTH` | rec | `true`. |
| `SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_ENABLE` | rec | `true`. |
| `MAYOCLONE_OTP_FROM` | rec | From-address on OTP + renewal emails (must be a verified sender). |

Works with any provider's SMTP relay (Amazon SES, SendGrid, Mailgun, Postmark,
Gmail SMTP). Once `SPRING_MAIL_HOST` is set, verify a real OTP arrives via
`POST /api/auth/otp/send` and keep `MAYOCLONE_OTP_DEV_MODE=false` so the code is
never returned in the response.
