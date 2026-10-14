## ADDED Requirements

### Requirement: Verified instrument and source identity
The system SHALL validate provider identity against symbol, market, exchange and instrument type, preserving leading zeroes and actual endpoint provenance instead of overwriting a mismatched response identity.

#### Scenario: Wrong market response
- **WHEN** a provider response identifies another market for the requested code
- **THEN** the observation is ineligible and no healthy risk assessment is derived from it

### Requirement: Source-time freshness and clock quality
The system SHALL separately preserve sourceAt, receivedAt, capturedAt and quality; missing source time, unverifiable clock skew and excess age SHALL NOT be replaced with a fresh local timestamp.

#### Scenario: Delayed but successful HTTP response
- **WHEN** HTTP succeeds but the source timestamp exceeds the verified freshness budget
- **THEN** coverage is stale or degraded and the response is not presented as timely market protection

### Requirement: Completed and covered path evidence
The system SHALL qualify minute bars using verified timestamp semantics, completion, OHLC validity, scale and coverage, rather than treating non-null values or startTime plus one minute as completion proof.

#### Scenario: Current partial minute
- **WHEN** the latest minute is still forming
- **THEN** it is excluded from completed-window features and final risk outcomes

#### Scenario: Missing price path
- **WHEN** required post-signal path coverage is unavailable
- **THEN** the system retains an explicit missing-path state rather than deriving no risk from daily low or sparse quotes

### Requirement: Interval features with explicit units
The system SHALL derive cumulative-volume and flow deltas only within matching source, trade date, units and statistical basis and SHALL preserve unknown features instead of substituting zero.

#### Scenario: Cross-day or revised cumulative flow
- **WHEN** cumulative flow resets across a date or source revision
- **THEN** the interval feature is invalidated without inventing a new inflow or outflow

#### Scenario: Valid negative net-flow delta
- **WHEN** verified signed cumulative net flow decreases within a valid matching interval
- **THEN** the negative delta is retained as an interval outflow proxy rather than rejected merely because of its sign

### Requirement: Bounded independent capture and coverage
The system SHALL capture active-position instruments with bounded concurrency, rate control and durable ownership, and SHALL account for missed eligible opportunities independently of successful captures.

#### Scenario: Model provider unavailable
- **WHEN** DeepSeek is unavailable while eligible market data is available
- **THEN** deterministic capture and protection continue without additional model submissions

#### Scenario: Process downtime
- **WHEN** planned monitoring opportunities were missed
- **THEN** coverage records the gap without backdating newly computed alerts

### Requirement: Capability-specific readiness
The system SHALL qualify quote protection, interval features and complete outcome paths independently; lack of minute or flow qualification SHALL NOT block otherwise eligible user price protection or authorize unqualified features and outcomes.

#### Scenario: Quote qualified but minute and flow contracts unknown
- **WHEN** identity, source freshness and price basis qualify for an explicit price policy while interval and path contracts remain unknown
- **THEN** price protection can pass its engineering gate independently while interval features and formal path outcomes remain unavailable

### Requirement: Isolation from canonical identity and frozen research
The system SHALL keep private positions and high-frequency risk research metadata out of canonical factHash and SHALL preserve priceFailure V1 inputs, protocol and owner.

#### Scenario: Enabling faster position capture
- **WHEN** the new assistant captures a risk observation
- **THEN** it does not change the identity or model-submission cadence of existing canonical facts and tasks
