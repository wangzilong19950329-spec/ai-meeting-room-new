## ADDED Requirements

### Requirement: Distinct output provenance and task meaning
The system SHALL distinguish trusted state descriptions, server heuristic profiles, model intent hypotheses, model forecasts and model risk language. Each result MUST carry its source and definition version; sampling cadence MUST NOT be represented as verified bar granularity.

#### Scenario: Trusted renderer and model forecast coexist
- **WHEN** trusted rendering replaces an analysis conclusion while the model supplied the forecast
- **THEN** consumers retain and display their different provenance and do not attribute the trusted conclusion to DeepSeek accuracy

### Requirement: Explicit availability and window timing
New tasks and result projections SHALL record source, observation, preparation, receipt, committed projection and capability availability times with quality. Model results available after the first target window begins MUST be separated as late results in primary forecast statistics.

#### Scenario: Delayed result enters the next trading session
- **WHEN** the model result becomes available after the primary forecast window starts
- **THEN** its capability remains auditable but is classified LATE_RESULT and cannot be presented as a timely primary forecast

### Requirement: Explicit forecast abstention
The new response contract SHALL permit each required horizon to return FORECAST or ABSTAIN. ABSTAIN MUST have a reason and no direction or target prices; SIDEWAYS MUST retain its concrete return-category meaning. Only valid FORECAST items SHALL create prediction rows.

#### Scenario: Insufficient directional evidence
- **WHEN** the model returns a valid T1 ABSTAIN item with an accepted reason
- **THEN** the capability is ABSTAINED, no fake SIDEWAYS prediction is created and the opportunity remains in coverage statistics

### Requirement: Capability-specific validation without repair submission
The new contract SHALL validate capabilities independently after root identity, contract and reference checks. A valid trusted base and at least one legal VALID or ABSTAINED capability result SHALL permit an explicitly partial projection. Legal all-horizon abstention SHALL complete without prediction rows. Invalid root or identity MUST fail the whole projection, and exclusively INVALID or UNAVAILABLE model results MUST NOT masquerade as model success. No validation outcome SHALL authorize a second model repair request.

#### Scenario: Valid T1 and invalid T5
- **WHEN** root and provenance are valid, T1 is valid and T5 has invalid prices
- **THEN** T1 is projected, T5 is recorded INVALID, partial availability is visible and no second provider call occurs

#### Scenario: Unparseable or identity-invalid root
- **WHEN** the result root cannot be parsed or cannot be tied to the frozen task
- **THEN** the task result remains invalid and no capability is manufactured as valid

#### Scenario: All required horizons legally abstain
- **WHEN** root, provenance and abstention reasons are valid and every required forecast horizon returns ABSTAIN
- **THEN** the task completes with explicit abstained capability results and zero forecast rows, and its opportunities remain in the coverage denominator

### Requirement: Confidence and band semantics
Probability and confidence SHALL include sourceKind, event, horizon, calibration status and version. New invalid probabilities MUST be rejected rather than clamped. Scenario bands MUST NOT be presented as fixed-coverage prediction intervals without a separate interval contract.

#### Scenario: Uncalibrated model probability
- **WHEN** a model-reported probability is stored without validated calibration
- **THEN** the UI identifies it as uncalibrated model reporting and never equates a 70 percent value with demonstrated 70 percent hit frequency

### Requirement: Market-aware and frozen-contract compatibility
Task roles and eligibility SHALL use the actual market and instrument mechanism. Frozen legacy tasks MUST retain their legacy parser and identity; changing response version MUST NOT authorize another submission for the same canonical observation.

#### Scenario: Legacy v6 result returns after v7 rollout
- **WHEN** a prepared or submitted v6 task completes while new tasks use v7
- **THEN** it is parsed under its frozen v6 contract without modifying the request or resubmitting the observation

#### Scenario: Unknown price-limit mechanism
- **WHEN** an instrument lacks verified applicable limit rules
- **THEN** the system does not apply a generic A-share near-limit label to it
