## ADDED Requirements

### Requirement: Preserve price-failure V1 isolation and protocol
The existing price-failure V1 SHALL retain its rule hash, forbidden inputs, no-backfill policy, frozen entries and 120 eligible source dates plus 30 mature signals unblinding gate. Any material change to outcome or entry semantics MUST use a new protocol version. Unblinding eligibility MUST NOT imply effectiveness.

#### Scenario: Model distribution phrase is found
- **WHEN** a stored model reply contains a strong distribution warning
- **THEN** that text is not imported into price-failure V1 features, assessments or outcome cohort

### Requirement: Independent model-warning research cohort
Model-warning research SHALL consume already persisted raw model replies and provenance without new provider calls. It MUST separately record strong warning, no warning, ambiguous, parse-failed, excluded, duplicate and late observations under a frozen extractor and prospective protocol.

#### Scenario: Negation or historical reference
- **WHEN** text says distribution is unconfirmed, negated, quoted or only historical
- **THEN** it is not counted as a current strong warning, and its declared extraction status is preserved

#### Scenario: Historic reply scanned today
- **WHEN** an old reply is processed after the research feature starts
- **THEN** it is exploratory and is not backdated as a timely prospective user-visible warning

### Requirement: Post-availability risk outcomes only
The research protocol SHALL freeze signal availability, entry proxy and outcome window before observing the outcome. Minimum return from entry MUST be named separately from strict path maximum drawdown or actual avoided loss. Prices before entry MUST NOT score a post-entry risk event.

#### Scenario: Entry occurs after the daily low
- **WHEN** a signal entry proxy occurs intraday after a possible daily low and no ordered post-entry path is available
- **THEN** the all-day low is not used as a post-entry hit and the required outcome is unscorable

### Requirement: Immutable research samples and terminal outcome revisions
Research SHALL freeze input provenance, features, baseline and first primary-signal identity separately from terminal outcome evidence. Duplicate consumption MUST be idempotent. Revised terminal outcome data SHALL require an appended revision, and research processing failures MUST NOT reverse a successful business analysis or resubmit its provider request.

#### Scenario: Research ingestion retries after business success
- **WHEN** sample processing fails after the source analysis committed and later consumes the same receipt again
- **THEN** the business analysis stays successful, exactly one source sample is retained and no extra provider submission occurs

### Requirement: Cohort-specific frozen controls and metrics
Research SHALL use pre-registered same-opportunity baselines, point-in-time controls and event de-duplication. Reports MUST include denominators, precision, cohort-limited recall, coverage, false warnings, latency and uncertainty, separating intraday and post-close strata.

#### Scenario: Repeated warning for one risk window
- **WHEN** several replies repeat a warning for the same stock and protocol-defined risk window
- **THEN** only the first eligible warning is the primary signal and later warnings cannot inflate independent sample count

### Requirement: Research cannot silently enable notifications
New model-warning research MUST remain isolated from production fact identity and notification triggers. Performance SHALL remain blinded until its frozen gate; any ordinary risk notification release requires its separate approved effectiveness and capability gate.

#### Scenario: Engineering smoke succeeds before validation
- **WHEN** parser, database and real-data ingestion tests pass but the prospective effectiveness gate is unmet
- **THEN** no new production warning or trade recommendation is enabled and ordinary UI exposes only permitted progress and quality information

### Requirement: Execution scenarios remain explicit proxies
Paper execution evaluation SHALL distinguish avoided new entry and existing sellable holdings, use post-publish qualified prices, instrument restrictions, costs and opportunity losses, and record unknown order or infeasible fills. It MUST NOT claim personal savings or actual trade performance without the corresponding evidence.

#### Scenario: Daily extrema cross stop and target
- **WHEN** the available data cannot determine the order or fill feasibility of both thresholds
- **THEN** evaluation records unknown ordering or frozen conservative bounds, not an idealized profitable fill
