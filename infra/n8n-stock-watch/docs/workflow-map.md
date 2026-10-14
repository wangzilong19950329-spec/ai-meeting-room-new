# Workflow Map

All workflow IDs are resolved from the n8n database by name after import. All
four definitions remain inactive.

## SW-00 Error Handler

`Error Trigger -> Normalize Error -> Persist Run Failure`

The other workflows are statically bound to the resolved SW-00 ID. n8n 2.29.10
does not execute an inactive error workflow, so runtime dispatch remains an
explicit activation boundary.

## SW-10 Minute Quote Ingest

`Schedule/Manual -> Runtime Gate -> China Session Guard -> Watchlist -> Java Adapter -> Quote Gate -> Previous Snapshot -> Delta/Approx Bar -> Persist`

- The Java adapter uses a dedicated header credential.
- Manual QA can exercise the source Gate outside a trading session.
- Stale, invalid, empty, or schema-invalid envelopes fail closed.
- Equal quote timestamps are treated as duplicates.
- Snapshot bars are labeled approximate; empirical units cannot produce VWAP.

## SW-20 Half-hour Analysis Pipeline

`Schedule/Manual -> Runtime Gate -> Slot Resolution -> Recover/Create -> Atomic Backlog Claim -> Packet Inputs -> De-identified Packet -> Provider Branch -> Strict Validator -> Persist`

- Slot creation and backlog dispatch are independent.
- PostgreSQL owns retry, lease, attempt, and idempotency state.
- DeepSeek uses model `deepseek-web-bridge`, capability `stock.technical-analysis`, a prompt version, packet hash, session key, and submission key.
- Zero-bar jobs become accepted `INSUFFICIENT_DATA` records rather than remaining RUNNING.
- Invalid JSON, transport failures, timeouts, unknown facts, unsupported hypotheses, extra schema fields, and unmapped narrative numbers are rejected.

## SW-30 Stress Report Publisher

`Schedule/Manual -> Runtime Gate -> Report Claim -> Position/Risk Inputs -> Eight Stress Scenarios -> Persist/Complete Claim -> Local Delivery Claim -> Mailpit`

- Report generation is claimed in `sw_report_claim`.
- Mail is allowed only for `MAILPIT` and `.invalid` recipients.
- Delivery rows are claimed before send and become `SENT_LOCAL_TEST`; an ambiguous stale send becomes `UNKNOWN` and is not retried automatically.
- Repeated publisher execution does not duplicate a report or email.

## Activation Boundary

Schedule nodes exist but no workflow is active. Production activation, external
SMTP, authoritative exchange calendars, and broker-position integration require
separate approval and Gates.
