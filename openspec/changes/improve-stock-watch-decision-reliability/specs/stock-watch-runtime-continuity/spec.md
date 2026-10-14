## ADDED Requirements

### Requirement: Stage-specific business freshness
The runtime status SHALL expose independent configuration, process, capability-readiness, business-success, freshness and maturity information using existing state owners. Query failures MUST yield UNKNOWN rather than healthy zero values.

#### Scenario: Bridge health without recent business results
- **WHEN** process or health checks pass but the applicable active policy has no fresh fact, demand, successful receipt or analysis
- **THEN** status identifies the stale or unknown stage and does not claim the analysis workflow completed

#### Scenario: Paused policy or market closure
- **WHEN** the relevant policy is paused or no due opportunity exists under the verified market calendar
- **THEN** lack of a new model result is not reported as a missed analysis deadline

### Requirement: Canonical single-submission recovery
Recovery MUST reconcile the canonical analysis task and exact Bridge receipt before any provider action. It SHALL preserve frozen request identity and MUST NOT retry an irreversible UNKNOWN submission or create a second provider ledger.

#### Scenario: Unknown provider outcome after restart
- **WHEN** a persisted task has a reservation or unknown submission outcome
- **THEN** recovery queries and reconciles the original task and submissionKey without automatically resubmitting the observation

### Requirement: Independent research progress visibility
Runtime status SHALL expose price-failure rule version and hash, scan state, cursor lag, assessments, exclusions and pending maturity without revealing blinded performance. Cursor lag MUST be distinguishable from a correctly processed zero-signal cohort.

#### Scenario: Zero research signals
- **WHEN** the ledger has zero signals
- **THEN** status distinguishes scan failure, unprocessed facts and processed non-triggered or excluded observations

### Requirement: Live workflow acceptance
Runtime recovery SHALL be accepted only with direct fact-to-demand-to-task-to-receipt-to-analysis-to-forecast evidence and actual mature-window evaluation, with deterministic facts and research still progressing during model failure.

#### Scenario: Successful complete window
- **WHEN** a real timely task succeeds and its target session later completes
- **THEN** the acceptance record links exact fact, demand, task, submissionKey, analysis, prediction and versioned mature evaluation IDs and verifies one provider submission

#### Scenario: Model unavailable
- **WHEN** the model provider fails or is unavailable
- **THEN** deterministic fact, risk and eligible research progression continues with explicit model capability failure
