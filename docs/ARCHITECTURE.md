# Architecture — mayoclone-api

MayoClone is a multi-tenant **IRCTC e-catering order aggregator**. Train-food
vendors receive order emails from IRCTC e-catering aggregators (Zoop, RailRestro,
Gofoodie, Comesum, TravelKhana, IRCTC eCatering). MayoClone ingests those emails,
routes each to the owning aggregator, parses the IRCTC fields, and exposes a
unified, per-tenant order feed with printable GST invoices.

- Stack: **Spring Boot 3.3.6, Java 21**, PostgreSQL + Flyway, Micrometer/Prometheus.
- Security model: [`../SECURITY.md`](../SECURITY.md)
- Ingestion setup: [`INGESTION.md`](INGESTION.md)
- Ops: [`RUNBOOK.md`](RUNBOOK.md)

## Data flow: ingest → route → parse → store → API → UI

```
                              ┌────────────── 3 mail sources ──────────────┐
                              │                                            │
 (1) FORWARDING  provider     │  (2) GMAIL_OAUTH        (3) IMAP           │
     webhook (PRIMARY)        │      pull                   pull           │
        │                     │        │                     │            │
        ▼                     │        ▼                     ▼            │
 POST /api/inbound/email      │  GmailMailSource       ImapMailSource     │
 (HMAC verified)              │  (Gmail REST)          (imaps + STARTTLS  │
        │                     │                         identity check)   │
        └──────────┬──────────┴───────────┬─────────────────┘            │
                   ▼                       ▼                              │
              RawMessage(from, subject, body, messageId)                 │
                   │                                                      │
                   ▼                                                      │
        ┌────────────────────┐   route: match `from` domain against every
        │   IngestionCore     │   active Aggregator.senderDomains
        │  (per-account)      │────────────────────────────────────────┐
        └────────────────────┘                                         ▼
                   │  matched aggregator            no route / parse fail
                   ▼                                         │
        ┌────────────────────┐                              ▼
        │ IrctcEmailParser    │                    ┌──────────────────┐
        │ (Generic / Zoop)    │                    │  ingest_failure  │  dead-letter,
        │ → ParsedOrder       │                    │  (review queue)  │  tenant-scoped
        └────────────────────┘                    └──────────────────┘  list/retry/delete
                   │  dedup on messageId + unique(aggregator_id, external_order_id)
                   ▼
        ┌────────────────────┐        ┌────────────────────┐
        │   irctc_order       │  ───▶  │  REST /api/orders   │ ───▶  React SPA
        │   + order_item      │        │  /stats /invoice    │       (mayoclone-web)
        └────────────────────┘        └────────────────────┘
```

## Components

| Layer | Key classes | Responsibility |
|-------|-------------|----------------|
| **Web / controllers** | `AuthController`, `OrderController`, `AggregatorController`, `VendorController`, `SyncController`, `InboundEmailController`, `GmailController`, `IngestFailureController`, `DemoController` | HTTP endpoints under `/api/**` |
| **Security** | `SecurityConfig`, `JwtService`, `JwtAuthenticationFilter`, `DoubleSubmitCsrfFilter`, `RateLimitFilter`, `AuthCookieFactory`, `CurrentAccountService`, `crypto/AesGcmStringConverter` | Stateless auth, CSRF, rate limit, column encryption |
| **Ingestion** | `ingest/MailSource` (+ `MailSourceRegistry`), `ImapMailSource`, `gmail/GmailMailSource`, `inbound/InboundSignatureVerifier`, `IngestionCore`, `RawMessage` | Fetch/receive mail, verify, route, dedup |
| **Parsing** | `parser/IrctcEmailParser`, `GenericIrctcEmailParser`, `ZoopEmailParser`, `ParsedOrder` | Extract IRCTC fields from the email body |
| **Services** | `OrderService`, `VendorService`, `AggregatorService`, `AuthService`, `RefreshTokenService`, `IngestFailureService`, `GmailOAuthService`, `AuditService`, `AggregatorSeeder`, `AccountSeeder`, `MailboxPollDispatcher` (+ durable job queue) | Business logic, tenant scoping |
| **Observability** | `observability/AppMetrics`, `CorrelationIdFilter` | Micrometer meters, `X-Request-Id` correlation |
| **Persistence** | `domain/*`, `repository/*`, Flyway `db/migration/V1..V4` | Entities + schema |

## The `MailSource` abstraction

`MailSource` is the pluggable interface for **pull-based** mail sources:

```java
public interface MailSource {
    MailSourceType type();               // IMAP or GMAIL_OAUTH
    List<RawMessage> fetch(Vendor vendor);
}
```

- `MailSourceType` = `IMAP`, `GMAIL_OAUTH`, `FORWARDING`.
- `ImapMailSource` and `GmailMailSource` implement pull; `MailSourceRegistry`
  picks the implementation for a vendor's `sourceType`.
- **FORWARDING is push, not pull** — it has no `MailSource`; the provider POSTs a
  `RawMessage` to `InboundEmailController`, which calls `IngestionCore` directly.
- All three paths converge on the same `RawMessage → IngestionCore` route → parse
  → dedup → store pipeline, so routing, dedup, dead-lettering and metrics are
  identical regardless of source.

## Entities

| Table | Migration | What it is |
|-------|-----------|------------|
| `account` | V1 | Tenant/business. Email + Argon2id password hash, role (OWNER/ADMIN). |
| `refresh_token` | V1 | Opaque refresh tokens (SHA-256 hash), family id, revoked flag, expiry. |
| `aggregator` | V1 | Managed IRCTC partner: code, name, `senderDomains`, subject hint, brand color, active. Seeded on empty startup; CRUD via `/api/aggregators` (writes = ADMIN). |
| `vendor` | V1 (+V2) | A mail source owned by an account. `sourceType`, IMAP host/port/user + **AES-GCM** `imapPassword`; FORWARDING `ingestAddress`; GMAIL_OAUTH `oauthEmail` + **AES-GCM** `oauthRefreshToken`. Restaurant name, station, GSTIN, address. |
| `irctc_order` + `order_item` | V1 | A parsed order (PNR, train no/name, coach/berth, station, passenger, delivery date/slot, items, amount), linked to an aggregator + (optionally) a vendor. Unique `(aggregator_id, external_order_id)`. |
| `ingest_failure` | V3 | Dead-letter row for mail that couldn't be routed/parsed. Tenant-scoped review queue. |
| `audit_log` | V4 | Append-only audit trail. UPDATE/DELETE blocked by a DB trigger. |

Schema is owned by **Flyway** (`V1__init`, `V2__mail_source`,
`V3__ingest_failure`, `V4__audit_log`); Hibernate runs `ddl-auto=validate` and
only checks the mappings line up.

## Tenancy

The tenant is `Account`. Every business row carries `account_id`, always derived
from the authenticated JWT (`CurrentAccountService`), never from the client.
Cross-tenant access returns 404. See [`../SECURITY.md`](../SECURITY.md).

## Scheduling

`MailboxPollDispatcher` can sweep active IMAP/Gmail vendors by enqueuing
`MAILBOX_SYNC` jobs onto the durable job queue (worker pool syncs them in
parallel), but is **disabled by default** (`mayoclone.poll.enabled=false`).
On-demand sync is via `POST /api/vendors/{id}/sync` or `POST /api/sync`. The
FORWARDING path needs no polling — it is event-driven by the provider webhook.
