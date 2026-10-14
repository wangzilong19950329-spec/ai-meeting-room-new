## ADDED Requirements

### Requirement: Completed and calendar-defined outcome windows
The forecast owner SHALL select target sessions by a versioned verified market calendar and instrument mechanism. It MUST NOT finalize an outcome from an unfinished bar or silently replace a missing target session with a later available stock bar.

#### Scenario: Target-day intraday bar exists
- **WHEN** the source provides a bar for the target date while its required trading window is unfinished or completion evidence is unverified
- **THEN** the evaluation remains WAITING_MATURITY or WAITING_DATA with an explicit reason and no final score

#### Scenario: Suspension or target-session data gap
- **WHEN** the target exchange session is over but the instrument has no qualified bar
- **THEN** the versioned retry policy records the gap and eventually an unscorable outcome rather than moving T1 to the next observed stock bar

### Requirement: Verified quote-basis prices
The system SHALL score source quote, target prices, target band and invalidation prices on one verified price basis with provenance. It MUST NOT mix unverified qfq and nominal prices, assume adjustment factor one, or treat a historical reconstruction proxy as production ground truth.

#### Scenario: Unresolved adjustment mismatch
- **WHEN** source and result prices use different adjustment bases without verified whole-window conversion evidence
- **THEN** the result is not scored and includes PRICE_BASIS_UNVERIFIED or CORPORATE_ACTION_UNRESOLVED

#### Scenario: Verified ordinary price outcome
- **WHEN** the completed outcome prices and source quote have a verified common basis
- **THEN** the result records the basis version, source snapshot, completion evidence and quote-basis price return, without claiming total return or execution profit

### Requirement: Append-only terminal evaluation revisions
StockWatchPredictionService SHALL own versioned evaluation child records. Original forecasts and legacy evaluations MUST remain unchanged. A terminal revision's definition, evidence and result MUST be immutable; revised data requires a new linked revision.

#### Scenario: Existing EVALUATED record is repriced
- **WHEN** an authorized re-evaluation batch processes a legacy EVALUATED prediction
- **THEN** it appends a new evaluation version and retains the original prediction and legacy score

#### Scenario: Concurrent or repeated batch
- **WHEN** two writers or repeated runs process identical prediction, evaluation version and revision
- **THEN** uniqueness and CAS prevent duplicate or conflicting terminal results

### Requirement: Explicit score meanings and denominators
The system SHALL separate direction-policy hits, RETURN_SIGN review, closing-band inclusion, extrema overlap and invalidation observation. It MUST NOT label extrema overlap as verified execution or invent an order of intraday events. Selected-event Brier SHALL require a valid event probability and use only scored-probability events in its denominator.

#### Scenario: Zero or missing return
- **WHEN** an UP or DOWN prediction has a trustworthy zero return
- **THEN** RETURN_SIGN treats it as non-divergent, whereas missing or non-finite returns are not silently zero and SIDEWAYS is outside the directional denominator

#### Scenario: Window extrema cross both thresholds
- **WHEN** daily extrema cover both a target and an invalidation threshold but no ordered path is available
- **THEN** the system reports overlap or observation and ORDER_UNKNOWN, not a profitable trade

### Requirement: Complete filtered statistics independent of pagination
The system SHALL provide all-range statistics with shared filters, event-selection policy, evaluation version, provenance, snapshot boundary and actual denominators. Page summaries MUST explicitly identify PAGE scope. Failed, late and abstained first planned eligible events MUST NOT be replaced by later successful forecasts.

#### Scenario: More than one page of records
- **WHEN** a fixed filter and snapshot contains 304 prediction records and the user loads them with different page sizes
- **THEN** the record-level aggregate reports 304 for each page size and any smaller event-level count has an explicit selection policy

#### Scenario: Selected event fails and later analysis succeeds
- **WHEN** the first eligible planned event has a provider failure or abstention and a later same-day task succeeds
- **THEN** the primary event retains its failure or abstention and the later record appears only in its declared secondary or record-level cohort

### Requirement: Auditable safe historical re-evaluation
Re-evaluation SHALL support dry-run, versioned batches, resumability, limits and per-record reasons. Reconstructed proxies MUST be distinguishable from verified outcomes, and old incomplete provenance MUST NOT be manufactured.

#### Scenario: Historical availability cannot be recovered
- **WHEN** a stored record lacks authoritative timing or price-basis evidence
- **THEN** the batch reports unscorable or exploratory-proxy status and excludes it from the verified main score
