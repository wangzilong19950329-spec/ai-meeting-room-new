## ADDED Requirements

### Requirement: Fast user-protection evaluation
The system SHALL evaluate explicitly configured protection conditions from eligible data without waiting for a model response or directional-forecast success, and SHALL NOT invent default user stop values.

#### Scenario: Price threshold reached with missing flow
- **WHEN** fresh verified price reaches an enabled user protection threshold and flow is missing
- **THEN** price protection triggers on the first eligible observation while flow capability is unavailable

#### Scenario: No configured threshold
- **WHEN** the user has not configured a protection value
- **THEN** the system does not silently select a stop percentage or amount

### Requirement: Monitoring-based trailing protection
The system SHALL bind trailing high-water marks to monitoring start, holding generation, policy version and verified price basis.

#### Scenario: Daily high predates registration
- **WHEN** the stock's daily high occurred before monitoring was armed
- **THEN** that high is not used as an observed trailing-protection reference

### Requirement: Separate evidence and validation provenance
The system SHALL distinguish USER_POLICY, OBSERVED_PATTERN and MODEL_HYPOTHESIS from validation state and SHALL NOT interpret heuristic scores, self-reported probabilities or distribution wording as verified risk probabilities or account intent.

#### Scenario: Strong model wording conflicts with direction
- **WHEN** a model warning coexists with an UP or SIDEWAYS directional forecast
- **THEN** both retain their provenance and independent meanings without one overwriting the other

### Requirement: Read-only model evidence and temporal eligibility
The system SHALL extract versioned current, conditional, ambiguous, negated and historical model-risk statements only from committed original replies, recording hashes, locations, receipts and actual available times.

#### Scenario: Historical quotation or negation
- **WHEN** a reply quotes a prior distribution warning or says distribution is not confirmed
- **THEN** it is not treated as a new unconditional strong warning

#### Scenario: Late model warning
- **WHEN** the interpretation becomes available after the condition or risk window has already occurred
- **THEN** it is marked late or archival, not counted as an advance warning

### Requirement: Episode transitions and escalation
The system SHALL freeze assessments and maintain CAS-protected risk episodes per owner, position generation, family and protocol, deduplicating repeated source observations without suppressing material severity upgrades.

#### Scenario: Repeated polling of unchanged data
- **WHEN** repeated captures refer to the same source observation
- **THEN** they do not create independent warnings or satisfy persistence requirements

#### Scenario: Critical protection upgrade during cooldown
- **WHEN** a new registered urgent protection condition is reached during ordinary notification cooldown
- **THEN** a distinct eligible upgrade transition can be published

### Requirement: Coverage is not risk resolution
The system SHALL keep coverage, episode status and severity orthogonal, requiring qualified recovery evidence to resolve an episode.

#### Scenario: Data disappears after a warning
- **WHEN** the quote source fails or becomes stale
- **THEN** the prior risk remains visible with degraded coverage rather than becoming safe

#### Scenario: No eligible first assessment
- **WHEN** a newly active position has no qualified market assessment yet
- **THEN** riskLevel is null with explicit coverage and reason instead of NONE

### Requirement: Existing capability and transaction isolation
The system SHALL preserve complete forecast and parallel intent functionality, frozen task parsing and single-submit ownership; supplemental extraction failure SHALL NOT roll back a committed canonical analysis.

#### Scenario: Supplemental risk parsing fails
- **WHEN** risk interpretation cannot be extracted from an otherwise committed analysis
- **THEN** existing forecasts and analysis success remain intact and supplemental failure is independently recorded
