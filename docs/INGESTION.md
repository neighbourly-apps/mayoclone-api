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

#### Mailgun (Routes → inbound parse)

- Add & verify the receiving domain; Mailgun sets the MX records for you.
- Create a Route: match recipient `.*@inbound.mayoclone.example`, action
  `forward("https://<api-host>/api/inbound/email")`.
- Enable webhook signing and set the signing key to
  `MAYOCLONE_INBOUND_SIGNING_SECRET`. (If your Mailgun plan signs with its own
  scheme, put a thin adapter in front that re-signs to the `X-Mayo-Signature`
  format, or use SendGrid.)

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
