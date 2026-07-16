# Stock Watch v17 Final Audit Packet

## Objective

- Primary: prevent false success, duplicate DeepSeek calls, stale-response projection, and unsupported intent conclusions.
- Secondary: preserve the existing stock-watch workflow, minimize account traffic, and make every fail-closed outcome traceable.

## Constraints

- Do not use a stale daily-Kline snapshot as current evidence.
- A Tencent fallback may be used only for mainland daily Kline and benchmark data, with explicit provider, series, adjustment, missing-field, request-hash, and fallback-reason provenance. It must pass date ordering, freshness, and quote OHLCV consistency gates before model use.
- Do not auto-retry an unknown provider outcome.
- Keep the existing `symbol.market` state-key format and dedicated 8791/9344 stock bridge.
- A missing telemetry update must not erase the durable task outcome, but successful analysis projection remains all-or-nothing.

## First Audit Findings And Adjudication

The first independent Challenge and Optimize round rejected the implementation. All three blocking findings were adjudicated as true:

1. Existing `UNKNOWN` / `INVALID_RESPONSE` / `FAILED` tasks could re-enter the generic catch path and attempt a second analysis insert under the same unique task ID.
2. A reused `RESPONSE_RECEIVED` / `PROJECTION_FAILED` response could be parsed against newly rebuilt evidence when the current prompt digest changed.
3. `DATA_UNAVAILABLE` was checked after `getOrCreateSession`, so a local data failure could create or rotate an empty DeepSeek session.

The revised implementation resolves terminal and in-progress tasks before the model path, restores a digest-verified frozen projection envelope for response replay, and runs the required-data gate before session creation. The second audit round found four additional material issues: submission identity included mutable quote/session fields, failure telemetry could roll back the durable failure, defaults still pointed at 8789/9333, and replay did not verify the response hash. Those findings were accepted and fixed. A later local self-review also made deterministic replay parse/schema failures terminal instead of leaving them permanently replayable.

## Claim Graph

### S1 (fact, high)

Statement: L0 monitor rules preserve `002979.sz` as symbol `002979`, market `sz`, and persist canonical observations under that market.

- dependsOn: E1, E2
- hiddenAssumptions: `StockSymbol.tryParse` remains the canonical parser for supported markets.
- falsifiers: a real response has an empty/wrong market or the fact row uses another market.

### S2 (fact, high)

Statement: a deterministic L0 rule does not build the external enhanced market bundle and does not call the legacy model judge.

- dependsOn: E1, E3
- hiddenAssumptions: local history context and deterministic risk calculations are not model calls.
- falsifiers: `buildBundle` or `judge(...)` is invoked by the tested non-legacy rule.

### S3 (fact, high)

Statement: missing required market data persists `DATA_UNAVAILABLE` without creating/rotating a session, reserving a model task, or calling DeepSeek.

- dependsOn: E1, E4, E5
- hiddenAssumptions: all technical bridge calls follow task reservation in this service.
- falsifiers: a data-gate result increments the session/task count or changes the bridge log.

### S4 (fact/inference, high)

Statement: same-symbol/source-date/run-slot re-entry has one stable `stock-analysis-v2` submission key independent of quote timestamp and session generation. Projected/terminal tasks return their existing analysis, in-progress tasks refuse duplicate work, and replayable responses require both response-hash integrity and the original digest-verified projection inputs. Deterministic replay parse/schema failures become `PROJECTION_INVALID` with one error projection.

- dependsOn: E1, E6, E7
- hiddenAssumptions: database contents are not maliciously rewritten together with both unkeyed digests.
- falsifiers: terminal re-entry inserts again, an in-progress task calls the bridge, or replay validates against current rather than frozen evidence.

### S5 (fact/inference, medium-high)

Statement: successful projection atomically commits analysis, predictions, model-intent tests, session update, and `PROJECTED_SUCCESS`; projection failure retains raw response for replay. Failure projection atomically commits terminal task plus error analysis, while a missing failure-session telemetry row is only a warning.

- dependsOn: E1, E7
- hiddenAssumptions: all database collaborators join the Spring transaction.
- falsifiers: fault injection leaves partial success rows, loses the raw response, or commits a terminal failure without its error analysis when analysis insert fails.

### S6 (fact, high)

Statement: intent-test age and support coverage advance once per distinct source trade date; a falsifier may resolve immediately, but support waits for maturity and required coverage.

- dependsOn: E1, E8
- hiddenAssumptions: accepted `source_date` is the canonical trade date.
- falsifiers: repeated same-day samples advance age, or one non-falsifier resolves a multi-day test as supported.

### S7 (fact, high)

Statement: the active backend, application defaults, and repository startup script use the dedicated stock bridge at 8791/9344, and the competing legacy LaunchAgent is disabled.

- dependsOn: E9
- hiddenAssumptions: no unmanaged process rewrites these environment values after login.
- falsifiers: restart routes technical analysis to 8789/9333 or the disabled LaunchAgent loads.

### S8 (fact, high)

Statement: a real `002979.sz` run accepted Tencent `qfqday` only after the latest Kline date matched the canonical quote date and the latest OHLCV matched the quote. The API exposes Tencent as the selected provider and identifies the fallback instead of silently presenting it as Eastmoney data.

- dependsOn: E10, E11, E12
- hiddenAssumptions: the Tencent qfq response contract remains `[date, open, close, high, low, volume]` for this endpoint.
- falsifiers: stale/out-of-order/future bars pass, the latest bar disagrees with the quote, or the API hides the selected provider and adjustment.

### S9 (fact/inference, high)

Statement: the final4 real model failure is caused by a prompt/validator contract conflict. The prompt requires a two-week historical-verdict statement to narrow intent, while every `mainForceIntent.evidence` item is independently required to cite a current `evidenceSignals.citationPaths` path. DeepSeek produced the required historical statement without a current-signal path, and the validator rejected the entire otherwise complete response.

- dependsOn: E13, E14
- hiddenAssumptions: the historical statement belongs inside `mainForceIntent.evidence`; the current schema offers no separate validated history-evidence field.
- falsifiers: the prompt already defines a valid historical citation namespace, or the rejected item actually contains an allowed current path.

### S10 (fact, high)

Statement: the final4 rejection failed closed without duplicate provider traffic or partial predictions. The provider outcome is durable in the Bridge ledger, a repeated POST with the same submission key/digest is served from the ledger with zero new Bridge log lines, the database task is terminal `INVALID_RESPONSE`, one linked error analysis exists, and prediction count for that analysis is zero.

- dependsOn: E15, E16
- hiddenAssumptions: Bridge log line count and persisted ledger belong to the same running 8791 process.
- falsifiers: the duplicate POST creates another `request_start`, or any prediction/success projection exists for analysis 767.

### S11 (fact/inference, high)

Statement: the prompt's stronger requirement to classify every prior two-week slot from programmatic `testVerdicts` is not satisfiable for the final4 input. The current receipt has no verdicts, the eight historical success rows predate `mainForceIntent`, and no intent-test row maps those historical technical claims to a falsifier. The model's claim that all eight warnings were `SUPPORTED` is therefore an unvalidated model inference, not a program verdict.

- dependsOn: E17
- hiddenAssumptions: no equivalent claim-to-test mapping exists outside the inspected technical-analysis, fact-receipt, and intent-test data.
- falsifiers: a persisted test/verdict row can be joined to each referenced historical claim, or the prompt clearly requires `UNRESOLVED` when no such mapping exists.

## Code And Fact Evidence

- E1 (direct): `mvn -q test` completed with 125 tests, 0 failures, 0 errors, 0 skipped. Node Bridge/client/ledger tests completed with 35 tests, 0 failures. `git diff --check` also passed.
- E2 (direct): `/tmp/aimeeting-v17-smoke-final2/monitor-response.json` contains a triggered deterministic rule plus a CREATED fact with symbol `002979`, market `sz`.
- E3 (direct): `StockWatchMonitorServiceTest.l0RulePreservesDotStockKeyAndDoesNotBuildEnhancedBundle` verifies no enhanced bundle and no legacy judge call.
- E4 (direct): `/tmp/aimeeting-v17-smoke-final3/technical-response.json` is HTTP 200 with analysis `766`, status `DATA_UNAVAILABLE`, provider `local_data_gate`, `modelCalled=false`, null session ID, and null conversation URL. History returns the same explicit state.
- E5 (direct): the final3 DB before/after query shows analysis `765 -> 766`, while session count/max stay `258/258` and task count/max stay `0/0`; bridge-log mtime/size stays `1783871356/626`.
- E6 (direct): `StockWatchTechnicalAnalysisServiceTest` covers stable same-slot identity without generation, terminal reuse, in-progress refusal, frozen evidence restoration, and projection-digest tamper rejection.
- E7 (direct): `StockWatchV17PersistenceSmokeTest` covers digest mismatch classification, response-hash verification, invalid-replay terminalization, success-projection rollback with reusable raw response, terminal/error failure commit despite missing telemetry, and rollback of the terminal transition when the analysis insert fails. `StockWatchAnalysisProjectionServiceTest` injects a telemetry SQL exception and verifies it is swallowed.
- E8 (direct): `StockWatchFactPersistenceServiceTest` covers same-day no-aging, maturity/coverage support, and immediate falsification; the H2 smoke verifies the persisted path.
- E9 (direct): the restarted launchd-owned backend process arguments use `http://127.0.0.1:8791/search` and debug port `9344`; status reports 8791 and bridge health reports the dedicated 9344 stock profile. `application.yml`, `StockWatchTechnicalAnalysisService`, `StockWatchAiJudgeService`, and `scripts/start-stock-watch-stack.sh` now share those stock defaults. `com.aimeeting.stock-watch-stack` remains disabled; the active owner is `com.aimeeting.manual.backend`.
- E10 (counterevidence): all three real technical runs report `dailyKline:unexpected end of stream` from `push2his.eastmoney.com`; a direct curl of that exact URL also gets `Empty reply from server` through the local network path. The bridge is healthy but was intentionally not called.
- E11 (alternative evidence): direct curl of Tencent's real qfq daily-Kline endpoint for `sz002979` returns HTTP 200 and 9,307 bytes for 120 bars. This proves that real current historical bars are reachable from another source, but does not by itself prove schema equivalence or authorize silent provider substitution.
- E12 (direct): `/tmp/aimeeting-v17-smoke-final4/run-response.json` record 767 exposes `sourceTrace.dailyKline.selectedProvider=tencent`, `series=qfqday`, `adjustment=qfq`, `fallbackUsed=true`, count 160, while the canonical quote and last Kline are both dated `2026-07-16` with open 60.54, close 58.51, high 62.25, low 58.16, and volume 167111. The real request reached DeepSeek and returned 2,829 answer characters in 97.5 seconds.
- E13 (direct): `StockWatchTechnicalAnalysisService.buildPrompt` simultaneously requires every two-week slot to be classified `SUPPORTED/WEAKENED/FALSIFIED/UNRESOLVED` and requires every `mainForceIntent.evidence/counterEvidence` string to contain a path from the current `evidenceSignals.citationPaths` list. No historical-ledger citation namespace is defined.
- E14 (direct): the real response's fifth intent-evidence item says that earlier overbought warnings were supported and narrows the hypothesis to possible secondary accumulation, but contains no `evidenceSignals.*` path. `validateMainForceIntent` reports `mainForceIntent.evidence[4]_missing_valid_evidence_path`, analysis 767 is `ERROR`, and no success projection occurs. Artifact: `/tmp/aimeeting-v17-smoke-final4/run-response.json`.
- E15 (direct): `/tmp/aimeeting-v17-smoke-final4/db-after.tsv` shows task 1 with stable key `stock-analysis-v2:sz:002979:DAY:2026-07-16:05:24`, status `INVALID_RESPONSE`, raw response length 4,403, and analysis 767 linked to task 1. `prediction_rows_for_analysis_767=0`.
- E16 (direct): `/tmp/aimeeting-v17-smoke-final4/bridge-log-delta.jsonl` contains exactly one request start/end. `/request-status` reports ledger `SUCCESS`; posting the same key/digest returns the persisted answer with `idempotentReplay=true`, and `/tmp/aimeeting-v17-smoke-final4/ledger-replay-log-proof.txt` records zero added Bridge log lines.
- E17 (direct/counterevidence): `/tmp/aimeeting-v17-smoke-final4/history-verdict-gap.tsv` shows record 767 has `testVerdicts=[]` and `createdTestCount=0`; the eight successful two-week rows have null historical intent fields, and `stock_watch_intent_test` has no `002979.sz` rows. The model nevertheless labels all eight earlier warnings `SUPPORTED` in E14.

## Design Boundary

The invariant is fail-closed and exactly-once per stable observation slot. Deterministic observations may persist independently. Model work starts only with current required data. A provider response becomes durable before database projection, and any replay projects only the inputs frozen with that response. Success projection is atomic; error-session telemetry is diagnostic and non-blocking. The patch does not hide unavailable upstream data behind stale cache or an unsafe model retry: a mainland daily-Kline fallback is accepted only after canonical consistency checks and remains explicit in source provenance.

## Alternatives Considered

- Reject any response when the current prompt digest changes: simpler, but strands valid raw responses after a projection-only failure and pressures another DeepSeek call.
- Rebuild projection inputs on retry: rejected because it can attach an old model answer to new evidence.
- Retry terminal or unknown outcomes automatically: rejected because it can duplicate account traffic and is unsafe for unknown provider outcomes.
- Make error-session telemetry transactional and mandatory: rejected because a missing telemetry row would erase the durable failure result and leave a task stuck in progress.
- Use stale Kline cache: rejected because it can attach a current quote to obsolete trend evidence.
- Add a provenance-preserving Tencent daily-Kline fallback: newly viable after E10/E11, but it needs field/adjustment/date equivalence tests, source metadata, freshness/consistency gates, and benchmark handling. It must not be implemented as silent substitution.
- Keep the current schema and merely tell the model to append a current-signal path to every historical statement: minimal, but it can create a false citation because a current numeric path does not identify which historical analysis or verdict is being referenced.
- Allow uncited historical prose in `mainForceIntent.evidence`: accepts the observed response, but weakens the evidence gate and cannot prove that a referenced historical record was actually in the frozen request.
- Add a separate structured `historyValidation` array with record/test identifiers, prior claim, verdict, implication, and supporting current paths: strongest semantics, but changes the response contract and parser.
- Build one frozen evidence catalog containing both current-signal paths and stable historical-analysis/test-verdict references, then validate all cited references through the existing evidence arrays: smaller outward schema change, but requires careful construction and replay tests so references cannot point outside the frozen request.

## Reasoning Map

E1-E3 support the L0 parsing and no-extra-model claims. E4-E5 prove the required-data gate has no session/task/bridge side effects. E6-E7 exercise task integrity, replay, and projection counterexamples. E1/E8 support distinct-trading-day semantics. E9 supports current topology. E10-E12 now prove the provenance-preserving Tencent fallback and a real DeepSeek response. E13-E14 isolate a live prompt/validator contradiction rather than a provider or data outage. E15-E16 prove that this rejection is terminal, idempotent at the provider boundary, and free of prediction partial writes. E17 shows the deeper historical-claim/test mapping is absent for this input, so a citation-only patch may accept fabricated verdict attribution. What remains unproven is the correct evidence contract for historical viewpoint validation and a real `PROJECTED_SUCCESS` under that contract.

## Audit Questions

1. Independently verify E1-E17 and classify S1-S11 as supported, weak, contradicted, or blocked.
2. Look for any remaining path that can call DeepSeek twice for one submission or project raw response against non-frozen inputs.
3. Check whether terminal/in-progress resolution and success/failure transactions can still leave a poisoned state under concurrency or rollback.
4. Check whether the data gate truly causes zero session/task/model side effects.
5. Independently verify S9: is the observed failure truly a schema-contract conflict, or is rejecting the model output the correct intended behavior with prompt-only correction sufficient?
6. Compare the four history-evidence alternatives above. Optimize for truthful provenance, frozen replay safety, minimal account traffic, and bounded implementation complexity; do not prefer a smaller patch if it permits fabricated historical attribution.
7. Specify the minimum tests and one real-data acceptance criterion required before coding can be called complete.
8. Return PASS only if the diagnosis and selected design are safe to implement. A final production `PROJECTED_SUCCESS` remains explicitly unproven at this audit stage.
