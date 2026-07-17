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

Statement: same-symbol/source-date/run-slot re-entry has one stable versioned submission key independent of quote timestamp and session generation. Projected/terminal tasks return their existing analysis, in-progress tasks refuse duplicate work, and replayable responses require both response-hash integrity and the original digest-verified projection inputs. Deterministic replay parse/schema failures become `PROJECTION_INVALID` with one error projection.

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

### S12 (fact, high)

Statement: the first v3 live attempt did not call DeepSeek. The request reached business logic, but the server rejected its own frozen history catalog because the digest depended on object iteration order. Recursive key sorting with null-field omission plus ordered JSON parsing makes the digest stable across persistence serialization, and a focused regression test now passes.

- dependsOn: E18, E19
- hiddenAssumptions: recursive canonicalization covers every JSON-compatible value used in the catalog.
- falsifiers: the same semantic catalog still hashes differently after database round-trip or reordered nested maps.

### S13 (fact/inference, high)

Statement: the v4 live attempt made exactly one real DeepSeek call and proved the historical-attribution contract: all eight legacy records were returned as `NOT_EVALUABLE`, with no fabricated server verdict and no use for narrowing. Projection nevertheless failed closed for two independent response-contract mismatches: `ONE_WEEK` was outside the allowed time-window enum, and one current-news evidence item cited `marketData.latestNews` although only `evidenceSignals.citationPaths` were allowed.

- dependsOn: E20, E21, E22
- hiddenAssumptions: the Bridge request/end pair belongs to the v4 task and the returned catalog digest was verified against its frozen request.
- falsifiers: any assessment differs from the server catalog, any legacy item was used for narrowing, no real provider call occurred, or either reported validation problem is absent.

### S14 (fact/inference, high)

Statement: the first v5 real run proves transport, frozen catalogs, historical attribution, projection, predictions, and idempotent replay, but it does not yet prove semantically trustworthy evidence. Task 4 reached `PROJECTED_SUCCESS`, analysis 770 is `SUCCESS/VALID`, T1/T5 predictions and two intent tests were atomically created, and an identical POST returned the same task/analysis with zero new Bridge lines. However, the accepted evidence still uses valid but semantically unrelated paths for MA60/MACD prose and mixes external-news facts with an unsupported price-suppression/placement motive.

- dependsOn: E23, E24, E25
- hiddenAssumptions: exact path membership was intended to establish more than traceability.
- falsifiers: the cited paths actually resolve to the stated MA60/MACD values, the motive text appears only in assumptions, or the repeated request generated provider traffic.

### S15 (fact, high)

Statement: `tail15MainSumYuan`, `tail15SuperLargeSumYuan`, and `tail15LargeSumYuan` are wrong for the real Eastmoney intraday-flow payload because each minute is a cumulative snapshot. Summing fifteen cumulative snapshots inflated the model evidence from a roughly -9.066 million 14:46-to-15:00 main-flow change to -544.942 million.

- dependsOn: E26, E27
- hiddenAssumptions: the endpoint's sampled `main/superLarge/large` fields retain their observed cumulative semantics.
- falsifiers: primary endpoint documentation or a controlled sample proves each row is an independent one-minute increment.

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
- E18 (direct): the authenticated v3 request returned HTTP 500 before provider submission with `history catalog digest` rejection; its task remained pre-submission and the Bridge log had no matching request. This is a server-side deterministic failure, not a model or account failure.
- E19 (direct): `historyCatalogDigest` now recursively canonicalizes nested objects through sorted maps, excludes the self-referential `catalogDigest`, omits null fields, and parses persisted objects with ordered fields. `StockWatchTechnicalAnalysisServiceTest.historyCatalogDigestSurvivesSerializationAndMapOrderChanges` passes.
- E20 (direct): `/tmp/aimeeting-v17-smoke-final5/day-v4-run.json` is HTTP 200 with analysis 768, `modelCalled=true`, task 3, and an otherwise complete DeepSeek response. The dedicated Bridge records one 52,684-character request, a 90,297 ms provider duration, and a 7,142-character answer.
- E21 (direct): the v4 response copies the frozen catalog digest and all eight catalog refs exactly, assigns every item `NOT_EVALUABLE`, leaves every `testRefs` list empty, sets every `usedForNarrowing=false`, and leaves `basisHistoryRefs=[]`. This directly reverses the fabricated-support behavior observed in E14/E17.
- E22 (direct/counterevidence): v4 is terminal `INVALID_RESPONSE`, with analysis 768 status `ERROR`, because the raw response used `mainForceIntent.timeWindow=ONE_WEEK` and cited `(marketData.latestNews ...)` in evidence item 4. The prompt currently says `marketData.latestNews` must be considered while separately requiring every evidence item to cite only a path from `evidenceSignals.citationPaths`; that list contains only computed fund-flow/price/position paths. The frozen snapshot contains ten concrete news records, including indexed title/time/url fields, so a precise current-news citation namespace can be generated without accepting a broad collection-level path.
- E23 (direct): `/tmp/aimeeting-v17-smoke-final6/day-v5-run.json` is HTTP 200 with task 4, analysis 770, `PROJECTED_SUCCESS`, `SUCCESS/VALID`, eight exact `NOT_EVALUABLE` legacy assessments, T1/T5 predictions, and two task-bound intent tests. The frozen request is contract v5 and contains a six-entry content-addressed external-evidence catalog.
- E24 (direct): `/tmp/aimeeting-v17-smoke-final6/day-v5-repeat.json` returns the same task 4, analysis 770, prompt hash, and conversation URL; Bridge log lines remain 1308 before and after. `/tmp/aimeeting-v17-smoke-final6/day-v5-citation-proof.json` resolves all 24 parsed paths against the frozen allowlist with zero unknown paths.
- E25 (direct/counterevidence): the accepted v5 evidence says MA60 is 54.5638 but cites `positionSignal.ma20` and `.ma5`; another item discusses MACD/DIF/DEA but cites only `.ma20` and `.return5Pct`. Its news item cites two real titles but appends a motive claim about depressing price for placement pricing or institutional accumulation. These statements pass because membership validation does not bind prose claims or numbers to resolved values.
- E26 (direct): analysis 770's frozen raw snapshot has 80 intraday-flow rows. The final 15 rows run from 14:46 to 15:00 and behave as cumulative snapshots: main flow moves from -30,457,363 to -39,523,364, while the current sum is -544,942,222; super-large moves from -57,729,873 to -66,368,525, while the current sum is -946,444,180.
- E27 (direct): `buildFundFlowEvidence` currently computes `tail15*SumYuan` by summing the `main/superLarge/large` fields of the final rows. For cumulative snapshots, a named interval delta must use the last snapshot minus the snapshot at the start boundary, not the sum of snapshots.

## Design Boundary

The invariant is fail-closed and exactly-once per stable observation slot. Deterministic observations may persist independently. Model work starts only with current required data. A provider response becomes durable before database projection, and any replay projects only the inputs frozen with that response. Success projection is atomic; error-session telemetry is diagnostic and non-blocking. The patch does not hide unavailable upstream data behind stale cache or an unsafe model retry: a mainland daily-Kline fallback is accepted only after canonical consistency checks and remains explicit in source provenance.

### Revised v6 Design After Round-2 Rejection

The path-only proposal is withdrawn. V6 uses server-defined evidence atoms:

- Each atom is generated deterministically from frozen evidence schema v2 and has `atomId`, exact source `path`, scalar `value`, `unit`, `asOf`, `source`, `category`, `predicate`, and explicit `supportIntents` / `counterIntents`.
- Main-force model output contains `supportAtomIds`, `counterAtomIds`, and `contextAtomIds`, not free-form evidence facts. Top-level technical output contains `evidenceAtomIds`. The server resolves IDs and renders UI-compatible fact strings; model-authored inference remains only in `stance`, `title`, `conclusion`, `assumptions`, `risk`, `watchPoints`, and forecast rationale.
- Support and counter IDs must be disjoint. Every selected support/counter atom must allow that role for the selected intent. Unknown, duplicate, null, stale, non-scalar, and context-only atoms fail closed.
- Strong intent requires at least one compatible `FUND_FLOW` atom and one compatible `PRICE_VOLUME` atom. Confidence above 0.55 additionally requires one compatible `POSITION_OR_TECHNICAL` atom. Context/news atoms never satisfy these gates and cannot justify intent by themselves.
- Curated atoms include exact MA5/MA20/MA60, MACD DIF/DEA/histogram, RSI14 and Bollinger values when available, plus derived predicates such as `price_below_ma20`, `macd_bearish`, `main_outflow`, and `tail15_main_delta_negative`. Bare values without a defined predicate do not count toward intent compatibility.
- Intraday-flow v2 treats rows as cumulative snapshots. `tail15*DeltaYuan` requires an ordered same-day sample at or before the exact end-minus-15-minute boundary and the end sample; it stores start/end timestamps and values. Missing boundary, cross-day, unordered, duplicate-time, or insufficient samples produce no atom and a limitation. V2 exposes no `tail15*SumYuan` aliases.
- V5 remains immutable. `ACTIVE_CONTRACT=6`, `SUPPORTED_REPLAY_CONTRACTS={5,6}`; parser, catalog verifier, signal schema, validator, and normalizer dispatch by the frozen version. V5 replay uses its frozen v1 signals and V5 validator without recomputation.
- New submission identity becomes contract-independent. Before claiming the stable key, the service checks the stable key and exact legacy v5/v4/v3/v2 keys for the same observation. Any existing state is returned/replayed/terminated under its own contract and prevents a second provider call. Contract version stays in the frozen envelope and task purpose, not the stable observation key.
- The successful V5 task is labeled `LEGACY_PATH_MEMBERSHIP_ONLY` in audit metadata; it remains usable for history continuity but is not retroactively promoted to V6 evidence quality.

Design claim R1: this atom contract does not prove the model's latent real-world intent inference, but it prevents the trusted evidence layer from containing model-invented values, irrelevant-path substitutions, role inversion, or news-only justification. It is a bounded relevance gate, not semantic entailment proof.

### Final v6 Design After Round-3 Rejection

R1's model-selected atom roles are withdrawn. The final candidate makes profile evaluation entirely server-owned:

- Split the model hypothesis into two axes: `positioningIntent=ACCUMULATE|DISTRIBUTE|NEUTRAL|UNKNOWN` and `executionTactic=PULL_UP|WASH|SUPPORT|SUPPRESS|ROTATE|NONE|UNKNOWN`. Preserve raw axes/confidence as audit-only fields. Existing compatibility `intent` is derived from the validated positioning axis; tactics are never collapsed into positioning.
- Freeze `atomCatalogVersion=2`, `roleProfileVersion=1`, staleness/cadence policy, units, input paths, and all composite derivation IDs in the v6 envelope. The model does not choose support or counter atoms.
- A closed server profile engine evaluates the complete atom catalog, automatically attaches all applicable support and mandatory counter atoms, deduplicates evidence families, and computes `evidenceTier=UNSUPPORTED|WEAK|STRONG` before looking at model confidence. Context/news atoms are excluded from profile scoring.
- Launch profiles are deliberately narrow. `ACCUMULATE`, `DISTRIBUTE`, and `PULL_UP` may reach only `WEAK` and confidence is capped at 0.55 when their exact same-window composite profile matches. `WASH`, `SUPPORT`, `SUPPRESS`, and `ROTATE` have no launch profile and always normalize to `UNKNOWN/UNSUPPORTED` until dedicated versioned multi-window/cross-sectional composites exist. `NEUTRAL` requires complete coverage plus an explicit balanced/no-dominant-signal profile. Missing or unmapped evidence defaults to `UNKNOWN`.
- Mandatory counters are derived from all atoms, cannot be hidden in context, and lower the server confidence cap. Model confidence never raises the tier or cap. Independent coverage counts by source family (`FUND_FLOW`, `PRICE_VOLUME`, `POSITION`, `TECHNICAL`), with position and technical momentum kept separate.
- Server renders all trusted evidence from atoms as structured bindings and UI-compatible strings. Model-authored motive remains visibly marked inference in assumptions/risk/watch points. If raw model axes are downgraded, the displayed main-force summary is replaced with a deterministic `UNKNOWN/NO_ELIGIBLE_PROFILE` explanation; the untrusted original remains only in raw response/audit metadata and does not narrow history or trigger alerts.
- Top-level technical evidence is also server-rendered from the complete/salient atom set; the model no longer writes trusted factual evidence strings. Free-form conclusion is explicitly inference-only and cannot upgrade the profile.
- Exact interval policy: `t1` is the fresh final sample within one declared source cadence of the observation cutoff. `t0=t1-15 minutes` must exist exactly. Both samples must be unique, ordered, same trade date/source request/scale, and within one continuous exchange segment. Missing boundary, lunch crossing, duplicate/out-of-order timestamp, stale end, or insufficient sample yields no delta atom and a limitation. `(14:45,15:00]` is the required 15-minute window.
- Add `stock_watch_analysis_observation_identity` with a unique contract-independent identity `(market,symbol,periodType,observationKey)` and canonical task FK. Claim/lock this row transactionally before task creation. Inside the lock, apply deterministic winner policy: `PROJECTED_SUCCESS`; then provider-submitted/replayable states; then terminal failures; then pre-provider reservations, with newer supported contract breaking equal-state ties. Bind one canonical legacy or stable task; abort and surface ambiguity for competing provider-submitted tasks.
- Migration is quiesced: stop the old backend/writers, create/backfill identity rows, abort startup on ambiguous nonterminal/provider-submitted duplicates, then start v6. Preserve each legacy task submission key for its frozen Bridge ledger. New task keys use contract-independent `stock-analysis-slot-v1` only after identity binding.
- Introduce complete immutable contract handlers for V5 and V6 covering parse, frozen schema/catalog verification, history checks, normalization, rendering, and projection. V5 uses frozen v1 signals and membership-only semantics. Unsupported replayable V2-V4 tasks terminalize locally without provider traffic; terminal/projected results remain returnable.
- V5 history entries carry `evidenceQuality=LEGACY_PATH_MEMBERSHIP_ONLY`; even later test outcomes cannot promote them into V6 narrowing evidence. They remain visible for continuity and explicit comparison.

Design claim R2: the trusted V6 layer guarantees observation-level exactly-once ownership, frozen-version replay, exact cumulative-flow deltas, complete server-derived counters, and bounded profile relevance. It still does not prove the real hidden actor's intent; the displayed result is an evidence-tiered hypothesis with unsupported tactics downgraded.

### Final v6 Design After Round-4 Rejection

R3 supersedes R1/R2 where they conflict. In particular, model-selected support/counter atoms are obsolete, `STRONG` is reserved and unreachable in launch profile v1, and all launch results are non-actionable.

#### Frozen atom and profile matrix

- `atomCatalogVersion=2` defines these same-day atoms from frozen scalar inputs: `FF_MAIN_STRONG_IN` (`mainNetInflowYuan >= 100000000` and `mainNetInflowRatioPct >= 2`), `FF_MAIN_STRONG_OUT` (both values at or below the negative thresholds), `FF_MAIN_BALANCED` (`abs(mainNetInflowYuan) < 100000000` and `abs(mainNetInflowRatioPct) < 1`), `PV_RETURN_UP` (`returnPct >= 1`), `PV_RETURN_DOWN` (`returnPct <= -1`), `PV_RETURN_BALANCED` (`abs(returnPct) < 1`), `POS_ABOVE_MA20` / `POS_BELOW_MA20` (`close > ma20` / `close < ma20`), and `TECH_MACD_BULL` / `TECH_MACD_BEAR` (`dif > dea && histogram > 0` / `dif < dea && histogram < 0`). Equality to MA20 or mixed MACD signs emit neither directional atom. Missing, stale, non-finite, unit-mismatched, or cross-date inputs emit no atom plus a limitation.
- Family deduplication key is `tradeDate|window|source|canonicalMetric`: the positive and negative predicates of one metric remain one family observation and can count at most once. Composite atoms retain every scalar binding used to derive them. The engine evaluates the entire catalog; the model cannot choose which atoms are visible.
- Positioning truth table is exhaustive. `ACCUMULATE` requires `FF_MAIN_STRONG_IN + PV_RETURN_UP`; `DISTRIBUTE` requires `FF_MAIN_STRONG_OUT + PV_RETURN_DOWN`; `NEUTRAL` requires `FF_MAIN_BALANCED + PV_RETURN_BALANCED`; `UNKNOWN` has no eligible profile. A raw axis is accepted only when its exact profile matches. A matched opposing profile, an incomplete profile, multiple eligible profiles, or any unmapped value normalizes to `UNKNOWN/UNSUPPORTED`.
- Mandatory positioning counters are exhaustive and automatically attached. For `ACCUMULATE`, `FF_MAIN_STRONG_OUT` or `PV_RETURN_DOWN` is a veto; `POS_BELOW_MA20` and `TECH_MACD_BEAR` each lower the cap to `0.45`. For `DISTRIBUTE`, `FF_MAIN_STRONG_IN` or `PV_RETURN_UP` is a veto; `POS_ABOVE_MA20` and `TECH_MACD_BULL` each lower the cap to `0.45`. For `NEUTRAL`, any strong-in/out or return-up/down atom is a veto. Veto always returns `UNKNOWN/UNSUPPORTED` with confidence `0`; non-veto counter reductions are non-additive and use the lowest declared cap.
- A non-veto positioning match yields `WEAK` only. Profile caps are `0.55` for `ACCUMULATE`/`DISTRIBUTE` and `0.35` for `NEUTRAL`; final confidence is `min(clamp(rawConfidence,0,1), profileCap, counterAdjustedCap)`. Model confidence cannot increase a tier or cap.
- Tactic truth table is also exhaustive: launch profile v1 has no eligible profile for `PULL_UP`, `WASH`, `SUPPORT`, `SUPPRESS`, `ROTATE`, `NONE`, or `UNKNOWN`. Every raw tactic therefore normalizes independently to `UNKNOWN/UNSUPPORTED`, confidence `0`, with no compatibility mapping into positioning. A future tactic profile requires a new frozen profile version.
- Positioning and tactic each persist their own `{rawValue, normalizedValue, tier, confidence, supportAtomIds, counterAtomIds, reasonCode, permissions}` object. There is no shared tier or cap. `STRONG` is reserved for a future profile version and is impossible under v1.

#### Downstream permissions and trusted rendering

- Launch `WEAK` and `UNSUPPORTED` are display-and-audit only. Both have `narrowingEligible=false`, `intentTestEligible=false`, `mainForceAlertEligible=false`, and `mainForceDeliveryEligible=false`. They cannot create an intent test, narrow later history, trigger an anomaly/main-force alert, or independently qualify an email. General scheduled technical-analysis delivery may still occur for other reasons, but it must use the deterministic server headline and must not present a weak main-force hypothesis as a signal.
- V6 trusted display uses deterministic text only: matched `ACCUMULATE`/`DISTRIBUTE` becomes `资金与价格同向的弱证据假设（非行动信号）`; matched `NEUTRAL` becomes `资金与价格未呈现显著方向（弱证据，非行动信号）`; every unsupported result becomes `主力意图证据不足（未满足可验证画像）`. The server renders the attached scalar bindings and counter facts. Model-authored main-force title, motive, analysis, and conclusion remain in raw/audit fields and are not shown in the normal UI or email.
- Top-level trusted title, stance, evidence, and technical summary are server-rendered from frozen atoms/signals. Model-authored risks, watch points, and forecast rationale may be displayed only under an explicit `模型推断` label and cannot alter profiles or permissions.
- Evidence quality is immutable. V6 profile output is `SERVER_PROFILE_V1`; V5 is `LEGACY_PATH_MEMBERSHIP_ONLY`; V2-V4 and unversioned rows are `LEGACY_UNVERIFIED`. Only a future quality with an explicit permission may narrow history. Test outcomes remain separate metadata and never upgrade the original evidence quality. Keyword inference in monitor/history readers is prohibited for all records carrying an explicit quality or V6 contract; canonical `UNKNOWN`/`NEUTRAL` is never replaced from title/conclusion text.
- Every active history, delivery, recovery, test-registration, and narrowing query must join the observation identity and require `task.id = canonical_task_id`. Noncanonical rows remain queryable only through audit diagnostics.

#### Exact close-window delta

- The close atom is market-specific and has no cadence tolerance. Mainland `SH/SZ` requires unique cumulative snapshots at exactly `14:45:00` and `15:00:00` Asia/Shanghai and computes `cumulative(15:00)-cumulative(14:45)` for the half-open interval `(14:45,15:00]`. Hong Kong requires exactly `15:45:00` and `16:00:00`. Unsupported markets emit no close atom.
- Both endpoints must share trade date, source request ID, scale/unit, and one continuous exchange segment. Missing endpoint, duplicate timestamp, out-of-order input, lunch crossing, date/source/scale mismatch, or non-finite value emits no atom and an exact reason code. A shifted rolling interval, if later needed, must use a different atom ID and persist its actual endpoints.

#### Canonical identity, writer fence, and total lifecycle matrix

- Identity normalization rejects blank fields and uppercases market/period while lowercasing the canonical symbol before inserting `stock_watch_analysis_observation_identity(market,symbol,period_type,observation_key)`. The short transaction inserts-or-selects the identity, handles duplicate insert by re-selecting, locks it `FOR UPDATE`, chooses/binds or creates one task, commits, and releases the lock before any Bridge/provider session begins.
- Migration installs a database writer-generation fence before backfill: MySQL task insert/update triggers require connection variable `@stock_watch_writer_generation=6`, while V6 Hikari initializes every connection with that value. Existing V5 pooled connections and a restarted old binary lack the variable and are rejected by the database. The operational order remains stop all old writers, install fence/schema, backfill, validate, then start V6.
- Winner classes are integrity-aware and total. Exactly one hash/envelope-verified `PROJECTED_SUCCESS` wins. Multiple projected rows with different response/projection hashes abort startup; byte-identical duplicates choose lowest task ID and quarantine the rest. With no projected row, exactly one hash/envelope-verified response-bearing row (`RESPONSE_RECEIVED`, `PROJECTION_FAILED`, or legacy `SUCCESS`) wins; multiple provider-touched rows with different hashes or unknown outcomes are ambiguous and abort. `SUBMITTED` is never tied against a response-bearing row by contract recency. Only when no provider-touched row exists may supported-contract recency and then lowest ID choose among `RESERVED` or terminal pre-provider rows.
- Every loser is irreversibly marked `NONCANONICAL_QUARANTINED` with the canonical task ID and diagnostic reason. Migration ambiguity diagnostics list identity, candidate IDs, contracts, states, request/response hashes, submitted timestamps, and legacy keys; startup fails before scheduling is enabled.
- Legacy V2-V4 handling covers every state without inference traffic. `RESERVED` becomes local `FAILED/UNSUPPORTED_CONTRACT_PRE_SUBMIT`. `SUBMITTED` performs one original-key Bridge ledger status lookup only: a completed body is persisted and terminalized `PROJECTION_INVALID/UNSUPPORTED_CONTRACT_RESPONSE_PRESERVED`; active, missing, malformed, or unknown status becomes durable `UNKNOWN/UNSUPPORTED_CONTRACT_PROVIDER_OUTCOME_UNKNOWN`. `RESPONSE_RECEIVED`, `PROJECTION_FAILED`, and legacy response-bearing `SUCCESS` verify and preserve frozen response/hash, write one diagnostic analysis atomically, then terminalize `PROJECTION_INVALID`. Existing terminal/projected rows remain returnable but noncanonical rows are audit-only. No legacy path may inference-submit or mint a new key.
- Contract V5 and V6 handlers are complete and immutable for parse, envelope/catalog verification, history quality, normalization, trusted rendering, and projection. V5 replay preserves its frozen output and quality; it is not recomputed with V6 profiles. A canonical V5 result for an observation blocks a V6 provider call for that observation.

Design claim R3: launch V6 provides deterministic, non-actionable evidence classification, exact market-close cumulative deltas, and one canonical provider owner per observation. It deliberately does not claim actionable main-force detection, semantic proof of hidden actor intent, or automatic history narrowing; those require separately audited future profile versions.

### Final v6 Design After Round-5 Rejection

R4 supersedes R3 where they conflict and closes the remaining migration/output cases:

- Trusted positioning is now derived only by the server profile engine. If exactly one positioning profile in the frozen truth table matches, the server emits that normalized value and its fixed cap; zero or multiple matches emit `UNKNOWN/UNSUPPORTED`. Raw model positioning/tactic/confidence are audit-only and cannot select, suppress, or raise a trusted result. Launch tactics remain entirely unsupported.
- V6 normal UI, email, API history, and future prompt history consume a dedicated server-rendered trusted DTO only. They never return or fall back to `rawResponse`, model title/conclusion, model main-force prose, risk/watch-point prose, or forecast rationale. Those fields are available only through an explicitly authorized audit endpoint or the original Bridge conversation URL. Forecast numeric fields remain subject to the existing structural/range validator, but their free-text rationale is hidden until a separately versioned semantic validator exists.
- Taskless legacy analyses are migrated before canonical-reader enforcement. Group key is normalized `(market,symbol,periodType,analysisDate,runSlot,periodKey-or-empty)`. Each group gets one synthetic task with purpose/status `LEGACY_HISTORY_ONLY/PROJECTED_SUCCESS`, generation `0`, a deterministic `legacy-history-v1:<sha256(groupKey)>` submission key, and no provider/submitted timestamp. The winner is the newest `SUCCESS` row, otherwise the newest row, with highest analysis ID as the final tie-breaker; it alone receives the synthetic `analysis_task_id` and identity binding. Other rows remain immutable audit-only. Synthetic history quality is always `LEGACY_UNVERIFIED`, with every active permission false; it cannot block or initiate provider recovery, narrow history, register tests, trigger notification, or qualify delivery. Its observation key is namespaced `legacy-history-v1:<sha256(groupKey)>`, so it cannot collide with a real provider observation identity.
- Provider ownership migration uses one deliberately conservative rule. `providerTouched` means `submitted_at IS NOT NULL` or a status in `SUBMITTED,RESPONSE_RECEIVED,PROJECTION_FAILED,SUCCESS,PROJECTED_SUCCESS,INVALID_RESPONSE,PROJECTION_INVALID,UNKNOWN`, except synthetic `LEGACY_HISTORY_ONLY`. If an identity has more than one provider-touched task, startup fails; no same-hash deduplication is attempted. If it has exactly one, that task is canonical only after status-specific integrity verification; failure aborts startup. A lone `SUBMITTED` is canonical and enters original-key ledger-only recovery. A projected task plus any other submitted/provider-touched task is therefore an ambiguity and aborts. If there are no provider-touched tasks, supported contract version then lowest task ID chooses among pre-provider/terminal candidates and all losers become `NONCANONICAL_QUARANTINED`.
- Status-specific integrity is exact. `RESERVED` requires a valid request digest/envelope and no submitted/response fields. `SUBMITTED` requires valid request digest/envelope, nonblank original key and submitted timestamp, and no response body/hash. Response-bearing states require `sha256(rawResponse)==response_hash`, `sha256(responseEnvelopeJson)==response_envelope_hash`, and matching submission key/request digest/response hash inside the response envelope. `PROJECTED_SUCCESS` additionally requires exactly one linked successful analysis. Any missing or contradictory field fails startup with identity, task ID, contract, state, and failing check in diagnostics. No projection-digest equivalence or automatic duplicate collapse is claimed.
- The integrity state table is exhaustive. `RESERVED` is pre-provider only as above. `SUBMITTED` is ledger-recoverable only as above. `UNKNOWN/PROVIDER_OUTCOME_UNKNOWN` and a submitted `FAILED` require a valid request envelope/digest, original submission key, submitted timestamp, nonblank terminal reason, and no linked successful analysis; with no response body, response body/hash/envelope must all be null, while an incomplete retained body requires a matching body hash, null response envelope, and `contentStatus != COMPLETE`. These are permanent terminal owners and can only be returned or audited, never ledger-polled, resent, or projected. `INVALID_RESPONSE` requires a nonblank raw body and matching body hash; V5/V6 also require a valid matching response envelope, while V2-V4 may lack it only when marked `LEGACY_RESPONSE_UNVERIFIED`, which is canonical for deduplication/audit but never replayed. `RESPONSE_RECEIVED`, `PROJECTION_FAILED`, legacy response-bearing `SUCCESS`, `PROJECTION_INVALID`, and `PROJECTED_SUCCESS` require the full matching response-envelope checks; the projected state also requires its one successful linked analysis. `NONCANONICAL_QUARANTINED` can never be a candidate. Synthetic `LEGACY_HISTORY_ONLY` follows only its separate migration rule. Any unlisted status, reason/field-shape mismatch, invalid hash, or unexpected linked analysis aborts startup.
- V5 lifecycle is total. `RESERVED` terminalizes locally as `FAILED/LEGACY_PRE_SUBMIT_NOT_RESUMABLE`. `SUBMITTED` may only inspect the original submission key: completed persists the response and runs frozen V5 replay; active remains canonical `SUBMITTED` and is polled without resubmission; after the frozen recovery timeout, missing/malformed/unknown becomes durable `UNKNOWN/PROVIDER_OUTCOME_UNKNOWN`. `RESPONSE_RECEIVED`/`PROJECTION_FAILED` replay frozen V5 inputs. Terminal/projected V5 returns as-is. No V5 recovery reconstructs or resends a prompt.
- The database mechanism is named the **provider/task-owner fence**, not a global writer fence. It guarantees only that an old connection cannot insert/update `stock_watch_analysis_task` and therefore cannot pass the mandatory reserve/submit transition used before a model call. Deployment must still stop and disable old binaries; taskless data-unavailable rows and delivery side effects are outside the trigger's guarantee and are controlled by quiesced deployment plus canonical readers.
- Fence/schema installation is fail-fast, never `safeExecute`: a dedicated startup migrator creates the identity schema and task triggers, queries `information_schema.triggers` to verify both triggers, opens a generation-6 connection probe that must succeed, opens an unset-generation probe that must be rejected, performs/backfills migration, validates zero ambiguous identities, and only then marks the V6 migration ready. Any DDL, trigger, probe, backfill, or invariant failure throws during context initialization, so scheduling beans never start.

Design claim R4: V6 has one provider/task owner per real observation, preserves taskless legacy history without granting it authority, exposes only deterministic trusted content on normal product surfaces, and fails startup rather than guessing across ambiguous provider outcomes. The task trigger is not claimed to fence every database side effect or replace deployment quiescence.

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
- Accept `marketData.latestNews` as one broad allowed path: rejected because it proves only that a collection existed, not which item supports the assertion or whether the cited title/time was present.
- Drop an invalid news evidence item and continue projection: rejected because it silently changes model meaning and may preserve a high-confidence intent after removing material support.
- Prompt-only enum correction: useful, but insufficient by itself because otherwise-valid providers commonly express `ONE_WEEK`; a bounded semantic alias (`ONE_WEEK -> FIVE_DAY`) can preserve meaning while still rejecting unknown windows. The normalization must emit a warning and remain covered by tests.
- Preferred current-news contract: deterministically enumerate exact paths for the compact frozen news payload (for example `marketData.latestNews[0].title` and `.showTime`), append only those present paths to `citationPaths`, instruct the model to copy one or more exact paths, and retain the every-item validator. This preserves frozen replay safety and lets the validator prove item-level availability.
- Keep free-form evidence strings plus keyword-to-required-path guards: improves MA60/MACD cases but remains incomplete and language-dependent; new indicators can bypass the guard until another real failure appears.
- Require structured `{claim, observations:[{path,value}]}` evidence objects: verifies copied scalar values and provenance, but the free-form claim may still attach an unsupported interpretation to correct observations and increases model formatting burden.
- Preferred v6 candidate for review: the model outputs only `evidencePaths` and `counterEvidencePaths`; the server resolves every path against the frozen signal/external catalogs and deterministically stores human-readable `path=value` evidence. Interpretive motive remains only in assumptions/conclusion/risk/watchPoints. This removes model-authored factual values from the trusted evidence layer, preserves UI-compatible string arrays after normalization, reduces JSON burden, and permits category coverage gates for strong/high-confidence intent.
- The path-only candidate above is rejected after round 2 because a real but irrelevant path can still be laundered into support. The revised design uses directional evidence atoms and role compatibility instead.
- Rename cumulative-flow fields to `tail15*DeltaYuan` and calculate the final cumulative snapshot minus the snapshot at the 15-minute boundary. Keep raw start/end values or timestamps in the frozen signal so the delta is auditable. Do not retain the misleading `Sum` aliases.

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
8. Decide whether bounded `ONE_WEEK -> FIVE_DAY` normalization plus exact indexed news-field citation paths is the best repair, or identify a materially safer design with similar account-traffic cost.
9. Independently verify S14-S15 and decide whether deterministic server-resolved `evidencePaths` is materially safer than free-form strings or structured claim/value objects.
10. Specify how v5 replay must remain supported after a v6 contract is introduced; the successful v5 task must not be reinterpreted under v6.
11. Return PASS only if the revised design is safe to implement. A mechanically successful v5 projection is proven; semantically trustworthy evidence and a real v6 acceptance remain unproven.
12. Audit revised design R1: look for intents with no safe atom mapping, role-inversion loopholes, stale/boundary errors, migration races, or ways context/news can leak into confidence gates. Return the minimum correction required for PASS rather than re-proposing path-only evidence.
13. Audit final design R2. In particular verify the two-axis downgrade policy, server-owned complete counters, exact 15-minute boundary, observation-identity transaction/winner policy, quiesced migration, and full V5/V6 handler dispatch. Return PASS only if this is safe to code; otherwise name a concrete remaining blocker that R2 does not address.
