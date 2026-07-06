# mayoclone-api

A mailbox-based **order aggregator**. Instead of integrating with each delivery
platform's API, mayoclone connects to a restaurant's own email inbox over IMAP,
parses the order-confirmation emails that Zomato / Swiggy / Uber Eats send, and
exposes them as **unified orders** over a REST API.

## Architecture

```
  IMAP mailbox(es)                parsers                 store            REST
 ┌────────────────┐   fetch    ┌──────────────┐  map   ┌──────────┐    ┌────────┐
 │  restaurant     │  UNSEEN   │ Zomato/Swiggy │ ──────▶│ OrderRecord│──▶│ /api  │
 │  inbox (Gmail…) │ ─────────▶│ /UberEats     │  dedup │  (H2/PG)  │    │  JSON  │
 └────────────────┘           └──────────────┘        └──────────┘    └────────┘
```

- **IMAP ingestion** (`ImapIngestionService`) connects, reads UNSEEN messages,
  extracts the text body (handles multipart; falls back to stripped HTML), and
  derives a stable message id (Message-ID header, else a hash of subject+date).
- **Parsers** (`AggregatorEmailParser` implementations) each declare `supports()`
  by sender/subject and `parse()` the body with regex, degrading gracefully on
  missing fields. Ingestion uses the first parser whose `supports()` is true.
- **Dedup** happens twice: on `sourceMessageId`, and on the unique
  `(aggregator, externalOrderId)` constraint.
- **Scheduling**: `PollScheduler` sweeps active mailboxes on a fixed delay, but
  is **disabled by default** (`mayoclone.poll.enabled=false`).

## Requirements

- Java 21
- No external infrastructure — runs on in-memory H2 out of the box.

## Run

```bash
./gradlew bootRun
```

The API comes up on `http://localhost:8080`. The H2 console is at
`http://localhost:8080/h2` (JDBC URL `jdbc:h2:mem:mayoclone`, user `sa`, no
password).

### Postgres profile (optional)

```bash
SPRING_PROFILES_ACTIVE=postgres \
POSTGRES_URL=jdbc:postgresql://localhost:5432/mayoclone \
POSTGRES_USER=mayoclone POSTGRES_PASSWORD=secret \
./gradlew bootRun
```

## Try the demo (no real mailbox needed)

The demo endpoint feeds 5 realistic sample emails (2 Zomato, 2 Swiggy, 1 Uber)
through the **real** parse → dedup → store pipeline.

```bash
curl -XPOST localhost:8080/api/demo/ingest
# -> {"newOrders":5}

curl localhost:8080/api/orders | jq
curl localhost:8080/api/orders/stats | jq
```

Each call mints **fresh unique order ids** via an internal counter, so repeated
`POST /api/demo/ingest` calls keep adding 5 new orders each time (they are not
deduped against each other). Real re-syncs of the *same* email, by contrast, are
deduped and add nothing.

## API

| Method | Path                             | Description                                           |
|--------|----------------------------------|-------------------------------------------------------|
| GET    | `/api/orders`                    | List orders (newest first). `?aggregator=ZOMATO` filter. |
| GET    | `/api/orders/stats`              | `{ total, totalRevenue, byAggregator: {...} }`        |
| GET    | `/api/mail-accounts`             | List mail accounts (never returns passwords).         |
| POST   | `/api/mail-accounts`             | Create a mail account. Body: label, imapHost, imapPort, username, password, useSsl. |
| DELETE | `/api/mail-accounts/{id}`        | Delete a mail account. `204`.                         |
| POST   | `/api/mail-accounts/{id}/sync`   | Sync one account now. Returns `IngestResult`.         |
| POST   | `/api/sync`                      | Sync all active accounts. Returns `IngestResult`.     |
| POST   | `/api/demo/ingest`               | Ingest sample emails. Returns `{ newOrders }`.        |
| GET    | `/actuator/health`               | Health check.                                         |

`IngestResult` = `{ fetched, newOrders }`.

## Security note

This is a slice: IMAP passwords are stored **in plaintext** in the DB, and the
API has **no authentication** (CORS is open to `http://localhost:5173`).

**Security TODO:** encrypt credentials at rest (or use app-specific IMAP
passwords / OAuth), and put the API behind auth before any real deployment.
