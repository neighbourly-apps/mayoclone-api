# mayoclone-api

An **IRCTC e-catering order aggregator** for train-food vendors.

A train-food vendor (a restaurant that delivers meals to passengers at a railway
station) receives order emails from IRCTC e-catering partners — **Zoop,
RailRestro, Gofoodie, Comesum, TravelKhana, IRCTC eCatering** — each carrying the
train, station, PNR, passenger, delivery slot and items. Instead of integrating
with every partner's API, the vendor simply **shares their mailbox at signup**;
we scrape it over IMAP, route each mail to the right aggregator, parse out the
IRCTC fields, and surface everything as **one unified order feed** with a
dashboard and **printable invoices**.

## Architecture

```
  vendor mailbox            route (aggregator table)      parse            store           REST
 ┌────────────────┐  IMAP   ┌──────────────────────┐    ┌────────┐     ┌────────────┐   ┌────────┐
 │  restro inbox   │ UNSEEN │ match sender domain →  │──▶ │ Irctc  │ ──▶ │ irctc_order │──▶│ /api  │
 │  (Gmail / …)    │ ──────▶│ Aggregator (Zoop, …)   │    │ Parser │dedup│  (H2 / PG)  │   │  JSON  │
 └────────────────┘        └──────────────────────┘    └────────┘     └────────────┘   └────────┘
```

- **Aggregators live in a DB table**, not an enum. The `aggregator` table is
  **seeded on startup** (only if empty) with the real IRCTC e-catering partners
  and is fully **editable via the REST API** (CRUD). Each row carries the
  `senderDomains` used to route scraped mail, plus a brand colour for the UI.
- **IMAP ingestion** (`ImapIngestionService`) connects per vendor, reads UNSEEN
  messages, extracts the text body (multipart-aware; falls back to stripped
  HTML), and derives a stable message id (Message-ID header, else a hash of
  subject+date).
- **Routing**: each mail's sender address is matched (case-insensitive) against
  every active aggregator's `senderDomains` to pick the owning `Aggregator`.
- **Parsers** (`IrctcEmailParser` implementations) turn the body into a
  `ParsedOrder`. `GenericIrctcEmailParser` regex-extracts every IRCTC field (PNR,
  train no.+name, coach/berth, delivery station code+name, passenger name+phone,
  delivery date+slot, line items, total) and handles **all** seeded aggregators
  so nothing is left unparsed. `ZoopEmailParser` extends it to refine a couple of
  Zoop-specific patterns. Parsing degrades gracefully with sensible defaults.
- **Dedup** happens twice: on `sourceMessageId`, and on the unique
  `(aggregator_id, externalOrderId)` constraint.
- **Scheduling**: mailbox polling runs on a **durable job queue** —
  `MailboxPollDispatcher` enqueues one `MAILBOX_SYNC` job per due vendor each
  dispatch tick and a bounded worker pool syncs them in parallel (a Postgres
  advisory lock keeps one dispatcher per tick). It is **disabled by default**
  (`mayoclone.poll.enabled=false`); the forwarding webhook + Gmail push still work.

## Domain

| Table          | What it is                                                                 |
|----------------|----------------------------------------------------------------------------|
| `aggregator`   | Managed IRCTC e-catering partner (code, name, senderDomains, brandColor…). |
| `vendor`       | A train-food restro; shares its mailbox at signup (IMAP creds, GSTIN…).     |
| `irctc_order`  | A parsed order (train/station/PNR/passenger/slot/items), linked to an aggregator. |

### Seeded aggregators

| Code              | Name            | Sender domains                          | Brand    |
|-------------------|-----------------|-----------------------------------------|----------|
| `ZOOP`            | Zoop            | zoopindia.com                           | #E4002B  |
| `RAILRESTRO`      | RailRestro      | railrestro.com                          | #C8102E  |
| `GOFOODIE`        | Gofoodie        | gofoodieonline.com                      | #F7941D  |
| `COMESUM`         | Comesum         | comesum.com                             | #00A651  |
| `TRAVELKHANA`     | TravelKhana     | travelkhana.com                         | #ED1C24  |
| `IRCTC_ECATERING` | IRCTC eCatering | ecatering.irctc.co.in, irctc.co.in      | #213A8F  |

Sender domains are **best-effort defaults** and are fully editable via
`/api/aggregators`.

## Requirements

- Java 21
- **PostgreSQL** — the primary datasource. Flyway owns the schema; Hibernate runs
  `ddl-auto=validate`.

## Run

The datasource is env-driven with safe local defaults
(`jdbc:postgresql://localhost:5432/mayoclone`, user/password `mayoclone`), so a
local Postgres with those defaults needs no extra config:

```bash
./gradlew bootRun
```

The API comes up on `http://localhost:8080`. Override the connection (and any
other setting) via env vars — see [`.env.example`](.env.example):

```bash
MAYOCLONE_DB_URL=jdbc:postgresql://localhost:5432/mayoclone \
MAYOCLONE_DB_USER=mayoclone MAYOCLONE_DB_PASSWORD=secret \
./gradlew bootRun
```

Local dev boots with **insecure dev-default** JWT/encryption/inbound secrets
(logged as warnings). Prod MUST override them — see the Production section.

## Signup → scrape flow

1. `POST /api/vendors` — a vendor signs up and shares their mailbox (restaurant
   name, station, phone, GSTIN, IMAP host/username/password). `imapUsername`
   defaults to `ownerEmail` when blank; `imapPort` defaults to 993, `useSsl` to
   true.
2. `POST /api/vendors/{id}/sync` (or `POST /api/sync` for all active vendors, or
   the scheduler when enabled) — connects over IMAP, reads UNSEEN mail, routes by
   the aggregator table, parses, dedups and stores orders, then stamps
   `lastSyncedAt`.
3. `GET /api/orders` — the unified feed; `GET /api/orders/{id}/invoice` prints an
   invoice.

## Try the demo (no real mailbox needed)

The demo endpoint feeds **6 realistic sample IRCTC emails** (2 Zoop + one each of
RailRestro, Gofoodie, Comesum, IRCTC eCatering) from their real sender domains
through the **real** route → parse → dedup → store pipeline.

```bash
curl -XPOST localhost:8080/api/demo/ingest
# -> {"newOrders":6}

curl localhost:8080/api/orders | jq
curl localhost:8080/api/orders/stats | jq
curl localhost:8080/api/orders/1/invoice | jq
```

Each call mints **fresh unique order ids and message ids** via an internal
counter, so repeated `POST /api/demo/ingest` calls keep adding 6 new orders each
time (they are **not** deduped against each other). Real re-syncs of the *same*
email, by contrast, are deduped and add nothing. Demo orders have no owning
vendor, so their invoices omit the vendor block.

## API

| Method | Path                          | Description                                                                 |
|--------|-------------------------------|-----------------------------------------------------------------------------|
| GET    | `/api/orders`                 | List orders (newest first). Filters: `aggregatorCode`, `station`, `date`, `trainNumber` (all optional, combinable). |
| GET    | `/api/orders/{id}`            | One order. `404` if missing.                                                |
| GET    | `/api/orders/stats`           | `{ total, totalRevenue, byAggregator:[{code,name,brandColor,count}], upcomingToday }`. |
| GET    | `/api/orders/{id}/invoice`    | Printable invoice (5% GST). See below.                                      |
| GET    | `/api/aggregators`            | List managed aggregators.                                                   |
| POST   | `/api/aggregators`            | Create an aggregator. `201`.                                                |
| PUT    | `/api/aggregators/{id}`       | Update an aggregator.                                                       |
| DELETE | `/api/aggregators/{id}`       | Delete an aggregator. `204`.                                                |
| GET    | `/api/vendors`                | List vendors (never returns `imapPassword`).                               |
| POST   | `/api/vendors`                | Vendor signup. `201`.                                                       |
| DELETE | `/api/vendors/{id}`           | Delete a vendor. `204`.                                                     |
| POST   | `/api/vendors/{id}/sync`      | Sync one vendor mailbox now. Returns `IngestResult`.                        |
| POST   | `/api/sync`                   | Sync all active vendors. Returns `IngestResult`.                           |
| POST   | `/api/demo/ingest`            | Ingest 6 sample emails. Returns `{ newOrders }`.                            |
| GET    | `/actuator/health`            | Health check.                                                              |

`IngestResult` = `{ fetched, newOrders }`.

### Invoice

`GET /api/orders/{id}/invoice` returns:

```
{ invoiceNumber ("MC-INV-000123"), issuedAt,
  vendor: { restaurantName, gstin, addressLine, stationCode, phone } | null,
  order:  { externalOrderId, pnr, trainNumber, trainName, coach, berth,
            deliveryStationCode, deliveryStationName, passengerName,
            deliveryDate, deliverySlot, aggregatorName },
  items:  [ { name, qty, price, lineTotal } ],
  subTotal, taxRatePct (5), taxAmount, total, currency }
```

Tax is **5% GST on food**. The **subtotal is the source of truth**: if the order
carries an explicit total (`order.amount`), that value is used as the subtotal;
otherwise the sum of the line items is used. `taxAmount` = 5% of subtotal,
`total` = subtotal + taxAmount.

## Production

MayoClone runs as a hardened, multi-tenant service. Before any real deployment,
read:

- **[SECURITY.md](SECURITY.md)** — security policy, auth/authz/tenancy model,
  secret handling + rotation, the three ingestion trust models, rate limits,
  headers, audit immutability, and a STRIDE threat model.
- **[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)** — components, data flow,
  entities, the `MailSource` abstraction.
- **[docs/INGESTION.md](docs/INGESTION.md)** — step-by-step setup for the
  forwarding webhook, Gmail OAuth (incl. the CASA caveat), and IMAP paths.
- **[docs/RUNBOOK.md](docs/RUNBOOK.md)** — deploy, required env/secrets, secret
  generation + rotation, Flyway behavior, backups, incidents, health/metrics.
- **[.env.example](.env.example)** — every env var with safe placeholders.

### Security model at a glance

- **Auth**: email + password (Argon2id) → **JWT access** (HS256, 15 min) +
  **opaque rotating refresh** (7 day, SHA-256-hashed, reuse ⇒ family revocation)
  in an httpOnly Secure SameSite=Lax cookie scoped `/api/auth`; **double-submit
  CSRF** on refresh/logout.
- **Tenancy**: all data scoped by `accountId` from the JWT (never client-supplied);
  cross-tenant access → **404**. Roles OWNER/ADMIN; aggregator writes = ADMIN.
- **Secrets at rest**: IMAP passwords + Gmail OAuth refresh tokens **AES-256-GCM
  encrypted** (`MAYOCLONE_ENC_KEY`); never returned in any DTO.
- **Hardening**: Bucket4j rate limits (10/min login, 300/min global → 429),
  HSTS/nosniff/DENY/CSP headers, CORS allowlist with credentials, and an
  **immutable audit log** (DB trigger blocks UPDATE/DELETE).
- **Ingestion**: forwarding webhook (HMAC-signed, no creds stored) is the primary
  path; Gmail OAuth and IMAP app-password are alternatives. Unroutable/unparseable
  mail dead-letters to a tenant-scoped review queue.

### Observability

- Micrometer → Prometheus at `/actuator/prometheus`
  (`mayoclone.orders.ingested`, `.ingest.failures`, `.webhook.inbound`,
  `.auth.login`, `.sync.duration`).
- JSON logs on the `prod`/`json` profile with `X-Request-Id` correlation.
- OTLP tracing wired (sampling `0.0` by default).
