# Ingestion Guide — mayoclone-api

MayoClone gets a vendor's IRCTC aggregator order emails through one of **three
mail sources** (`MailSourceType`). This guide covers setup, config, and the
trust/threat profile of each. They are listed in **descending order of trust** —
prefer the forwarding webhook.

All three converge on the same pipeline: `RawMessage → IngestionCore` → route (by
aggregator `senderDomains`) → parse → dedup → store, with unroutable/unparseable
mail dead-lettered to `ingest_failure`. See [`ARCHITECTURE.md`](ARCHITECTURE.md).

| # | Source | `sourceType` | Credentials stored | Trust |
|---|--------|--------------|--------------------|-------|
| 1 | Forwarding webhook (PRIMARY) | `FORWARDING` | **None** | Highest |
| 2 | Gmail OAuth | `GMAIL_OAUTH` | Encrypted refresh token | High (needs CASA) |
| 3 | IMAP app-password (fallback) | `IMAP` | Encrypted IMAP password | Lowest |

---

## 1. Forwarding webhook (PRIMARY)

The vendor forwards aggregator mail (or asks the aggregator to CC) to a
**per-vendor address** `<token>@<MAYOCLONE_INBOUND_DOMAIN>`. A mail provider
(Mailgun / SendGrid **inbound parse**) receives it and POSTs a JSON payload to
`POST /api/inbound/email`, signed with **HMAC-SHA256**. No mailbox credentials
are ever stored.

### How it works

- The vendor's `ingestAddress` is minted server-side and is **unique**.
- The provider signs each POST: header
  `X-Mayo-Signature: t=<unixSeconds>,v1=<hexHmac>` where
  `hmac = HMAC-SHA256(MAYOCLONE_INBOUND_SIGNING_SECRET, "<t>.<rawBody>")`.
- `InboundSignatureVerifier`: rejects (401) if the signature is missing/malformed,
  the timestamp skew exceeds **±5 minutes** (replay guard), or the HMAC doesn't
  match (constant-time compare).
- Recipient resolution:
  - matches a FORWARDING vendor → process, **200** `{status:accepted,newOrders}`
  - matches no vendor → **202** `{status:ignored}` (we don't reveal which
    addresses exist), nothing stored.
- Idempotent on `messageId`; emits `mayoclone.webhook.inbound{result}` + an audit
  event (`inbound.accept` / `inbound.reject`).

### Accepted JSON body

Canonical keys (Mailgun-style aliases in parentheses are also accepted):

```json
{
  "recipient": "<token>@inbound.mayoclone.example",   // or "to"
  "from":      "orders@zoopindia.com",                 // or "sender"
  "subject":   "Your IRCTC order is confirmed",
  "bodyPlain": "PNR ... Train ... Items ...",          // or "body-plain" / "text"
  "bodyHtml":  "<html>...</html>",                      // or "body-html"
  "messageId": "<abc123@zoopindia.com>"                // or "Message-Id"
}
```

### Setup

1. **DNS/MX**: point `MAYOCLONE_INBOUND_DOMAIN` (e.g. `inbound.mayoclone.example`)
   MX records at your inbound-email provider. *(Operator to-do.)*
2. **Signing secret**: generate one and set it on **both** sides —
   `MAYOCLONE_INBOUND_SIGNING_SECRET` on the API, and the provider's webhook
   signing config. `openssl rand -base64 32`.
3. **Env**: set `MAYOCLONE_INBOUND_DOMAIN` and `MAYOCLONE_INBOUND_SIGNING_SECRET`.
4. **Provider route** (examples below) → POST to `https://<api-host>/api/inbound/email`.
5. Create the vendor as `FORWARDING`; give the vendor its `ingestAddress` and a
   forwarding rule to set up in their mailbox.

#### Mailgun — NATIVE adapter (recommended): `POST /api/inbound/mailgun`

There's a **first-class Mailgun endpoint** that accepts Mailgun's own POST format
and verifies Mailgun's own signature — no re-signing shim needed.

- **Format**: Mailgun Routes POST the parsed message as
  `application/x-www-form-urlencoded` (or `multipart/form-data`). Fields consumed:
  `recipient`, `sender`/`from`, `subject`, `body-plain` → `stripped-text` →
  `body-html` (first non-blank wins; html is stripped to text), `Message-Id`, plus
  the signature triplet `timestamp`, `token`, `signature`.
- **Signature**: `signature = hexHMAC_SHA256(key = MAYOCLONE_MAILGUN_SIGNING_KEY,
  message = timestamp + token)`, constant-time compared. Freshness window ±15 min;
  a bounded in-memory recently-seen-`token` set blocks replay inside that window.
- **Status codes**:
  - **503** — `MAYOCLONE_MAILGUN_SIGNING_KEY` unset (feature disabled).
  - **401** — bad/missing signature, stale timestamp, or token replay.
  - **200** `{status:accepted,newOrders}` — signature ok AND recipient matched a
    FORWARDING vendor → processed (idempotent on `Message-Id`).
  - **200** `{status:ignored}` — signature ok but recipient unknown (no detail
    leaked; a 200 also stops Mailgun retrying). Nothing stored.
  - **406** — permanently unprocessable (e.g. no `recipient` at all) so Mailgun
    STOPS retrying.

Setup:

1. Add & verify the receiving domain in Mailgun; it sets the MX records for you.
2. Create a Route: match recipient `.*@inbound.mayoclone.example`, action
   `forward("https://<api-host>/api/inbound/mailgun")` (or `store(notify=...)`).
3. Copy Mailgun's **HTTP webhook signing key** (Dashboard → Sending → Webhooks)
   into `MAYOCLONE_MAILGUN_SIGNING_KEY`. That's the whole config — the adapter
   verifies Mailgun's signature natively.

> The generic `POST /api/inbound/email` (JSON + `X-Mayo-Signature`) still exists
> for providers that let you sign in our scheme (or a re-signing relay).

#### SendGrid (Inbound Parse)

- Settings → Inbound Parse → add host `inbound.mayoclone.example`, set the
  destination URL to `https://<api-host>/api/inbound/email`.
- Add the MX record SendGrid shows (`mx.sendgrid.net`).
- Sign the forwarded request with `MAYOCLONE_INBOUND_SIGNING_SECRET` (via an edge
  function / relay that computes `X-Mayo-Signature`).

### Trust / threat notes

- **No credentials at rest** — the biggest security win; a DB compromise leaks no
  mailbox access for this path.
- **Forgery** is blocked by the HMAC secret; **replay** by the ±5 min window.
  Keep the secret out of logs/VCS and rotate on suspicion.
- **Email spoofing**: a vendor forwarding attacker-crafted mail to their own
  address can only inject bogus orders **into their own tenant**. Enable
  SPF/DKIM/DMARC checks at the inbound provider to reduce this.

---

## 2. Gmail OAuth

One-click "connect a mailbox". The vendor grants read-only Gmail access; MayoClone
stores an **encrypted refresh token** and pulls mail via the Gmail REST API.

### Endpoints

- `GET /api/mail/gmail/connect` — **authenticated**. Returns the Google consent
  URL with a **signed `state`** binding the caller's `accountId`. Scope
  `gmail.readonly`, `access_type=offline`, forced consent (so a refresh token is
  always returned).
- `GET /api/mail/gmail/callback` — **public** route, but the signed `state` is
  validated in-handler to recover the account (tampered/expired → 400). Exchanges
  the code, stores the encrypted refresh token, 302-redirects to the SPA
  (`MAYOCLONE_FRONTEND_BASE_URL`).
- If Gmail is not configured (any of the three `GOOGLE_OAUTH_*` blank), both
  endpoints return **503** and the app still starts normally.

### Setup

1. In Google Cloud Console, create an OAuth 2.0 Client (Web application).
2. Authorized redirect URI = `GOOGLE_OAUTH_REDIRECT_URI`
   (e.g. `https://api.mayoclone.example/api/mail/gmail/callback`).
3. Set `GOOGLE_OAUTH_CLIENT_ID`, `GOOGLE_OAUTH_CLIENT_SECRET`,
   `GOOGLE_OAUTH_REDIRECT_URI` and `MAYOCLONE_ENC_KEY` (for token encryption).

### ⚠️ Google verification + CASA — REQUIRED at scale (operator to-do)

`gmail.readonly` is a **restricted scope**. Before serving **more than 100 users**
you MUST complete, with Google:

- **OAuth app verification** (brand/consent review), and
- a **CASA (Cloud Application Security Assessment)** by an authorized third-party
  assessor for the restricted scope.

This is an **external, multi-week process** (paperwork + a paid security
assessment) that the operator must budget for. **Until it's done, the app only
works for explicitly added Test Users** on the OAuth consent screen. Plan the
forwarding webhook (path 1) as the default and treat Gmail OAuth as opt-in.

### Trust / threat notes

- Refresh token encrypted at rest (AES-256-GCM); never returned in any DTO.
- `state` is signed → the callback is CSRF-resistant.
- Read-only scope limits blast radius; still, an `MAYOCLONE_ENC_KEY` compromise
  makes stored tokens decryptable — use a secret manager and rotate.

### Gmail push (production scale) — Pub/Sub + history sync

By default a connected Gmail mailbox is **polled** every 60s. That does not scale
to many restaurants (N mailboxes × poll interval × Gmail quota). The production
path replaces polling with **Google Pub/Sub push + Gmail history sync**, all
config-gated and OFF by default (blank config ⇒ falls back to polling; the push
endpoint returns 503).

**Moving parts**

1. **`users.watch`** — on Gmail connect (and via a renewal sweep) we register a
   watch on the INBOX label against a Pub/Sub topic. Gmail then publishes a tiny
   notification (`{emailAddress, historyId}`) to that topic on every change. A
   watch **expires after ~7 days**, so `GmailWatchRenewer` (`@Scheduled`, every
   `mayoclone.gmail.watch.renew-ms`, default 6h) re-watches any mailbox whose
   `gmail_watch_expiration` is null or within 24h. Removing a Gmail vendor
   best-effort calls `users.stop`.
2. **Push endpoint** — `POST /api/inbound/gmail/push` (public route; verifies
   itself). Pub/Sub delivers the envelope
   `{ "message": { "data": "<base64 {emailAddress,historyId}>", "messageId": "…" }, "subscription": "…" }`.
   - **Verification** (`GmailPushVerifier`):
     - `mayoclone.gmail.push.audience` set → require an **OIDC Bearer JWT** signed
       by Google (verified against Google's JWKS `oauth2/v3/certs`, cached), issuer
       `accounts.google.com` / `https://accounts.google.com`, `aud` == the
       configured audience, not expired. **Preferred / production-grade.**
     - else `mayoclone.gmail.push.token` set → require a matching `?token=` query
       param (constant-time compare).
     - else (neither) → endpoint **disabled → 503**.
     - Invalid auth → **401**.
   - **On a valid request**: decode the envelope; dedup on `message.messageId`
     (`processed_push` table — safe across instances); resolve the GMAIL_OAUTH
     vendor by `emailAddress`; **enqueue** a coalesced `GMAIL_HISTORY` job
     (`dedupKey = gmail-history:<vendorId>`, so a burst of pushes collapses into
     one sync); return **204** fast. Unknown email → **204** (ack + ignore, no
     leak). Malformed/undecodable body → **400**. Only genuinely transient
     failures surface as **5xx** so Pub/Sub retries.
3. **`GMAIL_HISTORY` job** (`GmailHistoryJobHandler`, on the durable queue) —
   refreshes an access token, pages
   `users.history.list?startHistoryId=<vendor.gmail_history_id>&historyTypes=messageAdded&labelId=INBOX`,
   fetches each added message `?format=RAW`, parses it (`MimeEmailParser`), and
   runs it through the shared `IngestionCore` (which dedups on Message-ID, so the
   job is idempotent). It then advances `gmail_history_id` to the newest
   `historyId`. **404 fallback**: when `startHistoryId` is too old Gmail returns
   404 → we list the recent 25 INBOX messages, ingest them, and **rebaseline**
   `gmail_history_id` from `users.getProfile` (logged as a WARN). The cold-start
   case (no baseline yet) uses the same recovery path.

**Poll interplay** — the 60s poll stays as a **fallback**. When a Pub/Sub topic is
configured and `mayoclone.poll.skip-gmail-when-push` is true (default), the
scheduled poll **skips GMAIL_OAUTH vendors** (push drives them); IMAP/other are
still polled. With no topic configured, Gmail vendors are polled as before. Both
paths dedup, so correctness holds either way.

**GCP / Pub/Sub setup (operator to-do)**

1. Create a Pub/Sub **topic**, e.g. `projects/PROJECT/topics/gmail-push`.
2. Grant Gmail permission to publish to it: give
   `gmail-api-push@system.gserviceaccount.com` the **Pub/Sub Publisher** role on
   that topic.
3. Create a **push subscription** on the topic whose **endpoint** is
   `https://<api>/api/inbound/gmail/push`, with **OIDC authentication** enabled
   (a service-account token; set its **audience** to the value you put in
   `MAYOCLONE_GMAIL_PUSH_AUDIENCE` — using the endpoint URL as the audience is a
   good default). Prefer OIDC over the `?token=` fallback in production.
4. Set the env vars: `MAYOCLONE_GMAIL_PUBSUB_TOPIC` (= the topic from step 1),
   `MAYOCLONE_GMAIL_PUSH_AUDIENCE` (= the OIDC audience from step 3). Reconnect
   each Gmail mailbox (or wait for the renewer) so `users.watch` registers against
   the topic.

Note: the same **Google verification + CASA** requirement above applies — the
`gmail.readonly` restricted scope and `users.watch`/history APIs need a verified
OAuth app to serve beyond Test Users.

---

## 3. IMAP app-password (fallback)

The vendor supplies IMAP host/port/username + an **app-specific password**. Plain
Gmail account passwords no longer work — the vendor must enable 2FA and create an
app password (or use their provider's equivalent).

### How it works

- Password stored **AES-256-GCM encrypted** (`imapPassword`, via
  `AesGcmStringConverter`); never returned in a DTO.
- `ImapMailSource` is hardened: `imaps` (implicit TLS) with a **server-identity
  check**, connect/read timeouts, and backoff. Reads UNSEEN messages, extracts the
  text body (multipart-aware; falls back to stripped HTML), derives a stable
  message id.

### Setup

1. Vendor enables 2FA on their mailbox and generates an app password.
2. Create the vendor as `IMAP` with host (e.g. `imap.gmail.com`), port `993`,
   `useSsl=true`, username (defaults to `ownerEmail`), and the app password.
3. Trigger `POST /api/vendors/{id}/sync` (or `POST /api/sync`, or enable
   `mayoclone.poll.enabled` for scheduled sweeps).

### Trust / threat notes

- **Lowest trust**: MayoClone holds standing credentials to the vendor's mailbox
  (even if app-scoped and read-oriented). Prefer paths 1 or 2.
- Mitigations: encryption at rest, TLS + identity check, app-password requirement.
- Residual: `MAYOCLONE_ENC_KEY` compromise ⇒ decryptable creds; a broad app
  password may grant more than read access depending on the provider.

---

## Parser tuning

Aggregators change their email formats without notice. Two tools help you tune the
regex parsers (`GenericIrctcEmailParser` + aggregator-specific parsers) against
real mail **without touching the database**:

### `POST /api/dev/parse-preview` (AUTHENTICATED, non-persisting)

A dry run of the exact route + parse the pipeline uses. Send either discrete fields
or a full raw RFC822 email:

```json
{ "from": "orders@zoopindia.com", "subject": "…", "body": "PNR: …", "raw": "<full .eml>" }
```

`raw` (a complete RFC822 email) is run through the MIME utility first to derive
from/subject/body. Response:

```json
{
  "matchedAggregator": { "code": "ZOOP", "name": "Zoop" },
  "parsed": {
    "externalOrderId": "ZOOP100245", "pnr": "4512367890",
    "trainNumber": "12951", "trainName": "Mumbai Rajdhani Express",
    "coach": "B3", "berth": "32", "boardingStationCode": "BCT",
    "deliveryStationCode": "NDLS", "deliveryStationName": "New Delhi",
    "passengerName": "Rajesh Kumar", "passengerPhone": "9876543210",
    "deliveryDate": "2026-07-08", "deliverySlot": "13:00-13:30",
    "amount": 520, "currency": "INR", "status": "CONFIRMED",
    "items": [ { "name": "Veg Biryani", "qty": 2, "price": 180 } ]
  },
  "wouldPersist": true,
  "warnings": [ "no line items parsed", "amount not found (or zero)" ]
}
```

`wouldPersist` is true iff an aggregator matched AND a parser produced an order.
Null fields are shown as `null`; `warnings` flags anything missing or defaulted.
Nothing is written to the DB, so it's safe to fire at production emails.

### `.eml` sample harness — `src/test/resources/samples/`

Drop a real aggregator `.eml` (mail client → "Show original" / "Download message")
into that folder and run:

```
./gradlew test --tests com.mayoclone.ingest.EmlSampleParsingTest
```

For **every** `.eml` it logs a concise parse report (matched aggregator + extracted
fields + warnings). Built-in `synthetic_*.eml` samples are hard-asserted; real
drop-ins are reported but never fail CI. See the folder's `README.md`.

---

## Dead-letter & retries

Any mail that can't be routed (no aggregator matches the sender) or can't be
parsed lands in `ingest_failure` instead of being dropped. It's a tenant-scoped
review queue:

| Method | Path | Action |
|--------|------|--------|
| GET | `/api/ingest-failures` | List failures for the caller's account |
| POST | `/api/ingest-failures/{id}/retry` | Re-run the pipeline for that message |
| DELETE | `/api/ingest-failures/{id}` | Discard the failure |

Watch `mayoclone.ingest.failures{reason}` to catch aggregator format changes early.
