# Phase3 Test Report

Date: 2026-08-01

## Automated Checks

- Java adapter/controller tests: 9 passed
- Phase2 domain tests: 15 passed
- combined DeepSeek and Phase2 Node suite: 81 passed
- repeatable `smoke-test-phase2.sh`: passed end to end
- repeatable `smoke-test-phase3-retry.sh`: passed `504 -> RETRY -> SUCCESS`
- migrations: `V001` through `V011` applied with checksums intact
- workflow binding: four unique names, resolved IDs, all inactive

## Live Gates

- Java adapter auth: missing 401, incorrect 401, correct 200
- Java CATL envelope: schema v1; weekend quote marked `STALE`
- Java stale workflow path: SW-10 rejected `QUOTE_REJECTED:STALE`
- DeepSeek facade `/v1/models` auth: missing 401, incorrect 401, correct 200
- DeepSeek facade `/search` auth: missing 401, incorrect 401, correct token reached
  request validation
- n8n container reachability: Java and DeepSeek endpoints returned 200 with credentials
- technical account: routing eligible, identity verified, circuit closed
- controlled Java runtime: no scheduled-job activity observed before shutdown
- injected HTTP 504: first attempt entered `RETRY` with `MODEL_TIMEOUT`; the
  second attempt succeeded, used one stable packet/model row, and SW-30 created
  one report linked to the accepted validation
- broker ledger semantics: first provider attempt was `FAILED_PRE_SUBMIT`, the
  second was `SUCCESS`, with two distinct attempt-scoped submission keys
- unknown-outcome safety: an after-submit timeout became
  `MODEL_PROVIDER_OUTCOME_UNKNOWN`, stayed terminal at attempt one, and a second
  SW20 execution did not invoke the provider again
- OpenAI adapter contract: `providerOutcomeUnknown` and `retrySafe` survive the
  `/search` error to `/v1/chat/completions` mapping
- stale-attempt fencing: a superseded lock token produced zero job updates

## DeepSeek Canary

Prompt v1 returned valid JSON but transformed fact values into narrative
percentages. The validator retained it as `POLICY_VIOLATION` and marked the job
FAILED.

Prompt v2 prohibited digits and numeric transformations in narrative text. The
next independent job completed as:

```text
provider=DEEPSEEK_WEB
model_status=STRUCTURE_VALID
validation_status=accepted
model_input_leaks_symbol=false
job_status=ANALYZED
```

The accepted run then produced a `STRESS_ONLY` report with eight deterministic
scenarios and one `SENT_LOCAL_TEST` Mailpit delivery. A second publisher run did
not duplicate the email.

## Remaining Boundaries

- Workflows and schedule triggers remain inactive.
- External SMTP was not configured or tested.
- SW-00 is bound but cannot run while inactive.
- Eastmoney volume/amount units remain empirical; VWAP is unavailable.
- The trading calendar uses weekday/session windows, not an authoritative CN holiday calendar.
- Code-node consolidation and audit-location fingerprints remain
  activation-hardening work.
- The existing loopback host broker on `8791` has not loaded the new all-route
  auth rule because the sandbox cannot stop its user-owned process. The n8n
  route through authenticated facade `8793` is live and verified.
