## ADDED Requirements

### Requirement: Pre-registered outcome and release objectives
Each formal experiment SHALL freeze its primary outcome, primary baseline, cohorts, deduplication, availability rules, minimum useful effect, false-warning and coverage constraints, uncertainty method and release decision before outcome inspection. Exploratory historical selection MUST NOT be presented as holdout proof.

#### Scenario: Candidate chosen from historical successes
- **WHEN** a threshold or phrase was selected after inspecting historical declines
- **THEN** its historical performance is exploratory and formal validation uses subsequent unseen eligible samples

### Requirement: Temporal and overlapping-label isolation
Experiments SHALL group related stock-date events and isolate future holdout windows. Splits MUST remove overlapping label windows and future-visible features; random prediction-row splitting MUST NOT establish robustness.

#### Scenario: T5 labels overlap a holdout boundary
- **WHEN** an earlier event's outcome window extends into the holdout evaluation period
- **THEN** that event is purged or otherwise excluded under the frozen split contract rather than leaking into training or rule selection

### Requirement: Controlled input increments and reproducibility limits
Input experiments SHALL compare pre-fixed feature increments and record fact snapshot, prompt, parser, session, FULL/DELTA and provider metadata. Uncontrolled web-model state MUST be recorded as a limitation. Replaying a stored reply MUST NOT be claimed as a model-input counterfactual experiment.

#### Scenario: Stored reply is replayed under a new parser
- **WHEN** the same raw output is rescored or reparsed offline
- **THEN** the result validates parsing or scoring only, not an improvement from a changed model input

### Requirement: Production call budget and namespace isolation
Production SHALL retain one irreversible provider submission per canonical observation. Multi-version model experiments MUST require an approved additional budget and isolated research namespace, session and result storage, without feeding alternatives into production forecasts or notifications.

#### Scenario: Extra budget is not approved
- **WHEN** an experiment proposes multiple model variants for one event
- **THEN** it cannot create extra production submissions and must remain offline or await a separately authorized research execution

### Requirement: Event-specific calibration
Calibration SHALL use truthful versioned labels, fixed event and horizon, time-separated fitting and unseen evaluation. Model-reported and heuristic values MUST remain uncalibrated until their own gate is met, and selected-direction binary scores MUST remain distinct from multiclass probability scores.

#### Scenario: Calibration samples are insufficient
- **WHEN** a bucket or protocol lacks enough independent information to support calibration
- **THEN** the report shows sample counts and insufficient evidence rather than publishing a precise calibrated probability

### Requirement: Separate engineering acceptance from effectiveness
Builds, tests, schema readiness and runtime smoke SHALL establish engineering acceptance only. Effectiveness release MUST require the frozen incremental-effect, coverage, latency, false-warning and cost gates; execution claims additionally require execution evidence.

#### Scenario: Accurate subset has low coverage or late signals
- **WHEN** a candidate has an attractive hit rate but violates its frozen coverage or availability constraint
- **THEN** it fails or remains inconclusive for release and cannot be declared a usable improvement
