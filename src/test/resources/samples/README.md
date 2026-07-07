# Sample aggregator emails (`.eml`)

This folder is a **drop-in harness for real IRCTC e-catering order emails**.

## What's here

Four **synthetic** (fake but realistic) RFC822 multipart emails resembling real
aggregator order confirmations:

| File | Aggregator | Routes by sender domain |
|------|------------|-------------------------|
| `synthetic_zoop_order.eml`            | Zoop            | `zoopindia.com` |
| `synthetic_railrestro_order.eml`      | RailRestro      | `railrestro.com` |
| `synthetic_comesum_order.eml`         | Comesum         | `comesum.com` |
| `synthetic_irctc_ecatering_order.eml` | IRCTC eCatering | `ecatering.irctc.co.in` |

## Drop your real emails here

1. In your mail client, **"Show original" / "Download message"** to get the raw
   `.eml` (RFC822) and save it into this folder.
2. Run the harness:

   ```
   ./gradlew test --tests com.mayoclone.ingest.EmlSampleParsingTest
   ```

3. Read the log output. For **every** `.eml` it prints a concise parse report:
   the matched aggregator, the extracted `ParsedOrder` fields, and any warnings
   (missing PNR / amount / items / delivery station, etc.). Use this to tune the
   regexes in `GenericIrctcEmailParser` (or add an aggregator-specific parser).

## CI safety

Only files named `synthetic_*.eml` are **hard-asserted** (they must extract PNR,
train number, amount, at least one item, and a delivery-station code). Any real
file you drop in is parsed and **reported but never fails the build**, so a real
email that parses imperfectly won't break CI while you tune.

> Redact personal data (passenger name/phone/PNR) before committing any real
> sample to version control.
