## ADDED Requirements

### Requirement: Complete prospective opportunity accounting
The system SHALL freeze eligible holding opportunities before observing future outcomes and retain no-warning, missed, data-ineligible, model-unavailable, failed-delivery and unscorable states.

#### Scenario: Monitoring produces no warning
- **WHEN** an eligible planned opportunity has no risk warning
- **THEN** it remains in the opportunity cohort and can contribute to leakage-safe recall evaluation

#### Scenario: Missed opportunity has no publication time
- **WHEN** a scheduled opportunity was not executed but a qualified future outcome path exists
- **THEN** the preregistered common scheduled-opportunity outcome protocol can label that path without inventing availableAt, and any warning match uses its actual publication time before the risk event

### Requirement: Post-availability risk windows
The system SHALL freeze actual availableAt, an explicitly qualified post-availability anchor and verified REMAINDER_SESSION or NEXT_SESSION windows, without moving targets to the next downloaded stock bar.

#### Scenario: Low occurred before warning
- **WHEN** a daily or minute low predates or straddles signal availability
- **THEN** it is not counted as a post-signal risk hit

#### Scenario: Minute-only future anchor
- **WHEN** the registered protocol starts at the next complete post-signal minute
- **THEN** the excluded partial interval and anchor delay are disclosed rather than included as evaluated protection

### Requirement: Complete-path outcome quality
The system SHALL separate complete-path outcomes from sampled observed risk and SHALL NOT treat absence of a sampled threshold hit as evidence of no full-window risk.

#### Scenario: Sparse quotes show no threshold breach
- **WHEN** only sparse post-signal quote samples are available
- **THEN** the system cannot publish a complete-path negative outcome from those samples

### Requirement: Independent metrics and denominators
The system SHALL report protection detection quality separately from inferred-risk precision, observable-cohort recall, warning lead time, late rate, coverage and false warnings per holding day; directional hit rate SHALL NOT replace these metrics.

#### Scenario: Direction wrong but timely risk event observed
- **WHEN** a SIDEWAYS forecast is wrong while a separately eligible risk warning precedes an adverse event
- **THEN** the two results are independently scored under their own definitions

### Requirement: Execution and personal-effect provenance
The system SHALL separately report market-risk events, execution proxies and user-reported trade comparisons, incorporating sellability, delays, costs, unfillable conditions and forgone upside.

#### Scenario: Same-day new A-share lot cannot exit
- **WHEN** the evaluated scenario includes an ordinary A-share lot newly bought today
- **THEN** it does not assume immediate sale of that lot or count all later decline as avoided loss

#### Scenario: Price rises after warning
- **WHEN** an exit proxy is compared with holding through the frozen terminal window and holding has higher net value
- **THEN** the difference is recorded as negative benefit or forgone upside rather than silently excluding the warning

#### Scenario: Only part of the holding can exit
- **WHEN** 1000 of 1500 registered shares are eligible to exit in the frozen comparison
- **THEN** the exit and hold strategies both include the same remaining 500 shares, or the incremental comparison uses only the same 1000-share quantity on both sides

### Requirement: Frozen terminal revisions and complete statistics
The system SHALL preserve original inputs and terminal outcome revisions, append superseding revisions explicitly, and return full-filter statistics aligned with list snapshot and evaluation version.

#### Scenario: Provider repairs historical bars
- **WHEN** corrected bar evidence arrives after an evaluation is terminal
- **THEN** a new linked revision is required and the old result is not overwritten

### Requirement: Prospective approval and frozen legacy protocols
The system SHALL require a preregistered event, baseline, utility and uncertainty plan before trusted inferred-risk enablement, while preserving priceFailure V1 rules and unblinding requirements.

#### Scenario: Local smoke passes but no prospective evidence exists
- **WHEN** engineering tests pass for a novel inferred-risk rule
- **THEN** it remains shadow or explicitly experimental rather than being enabled as a trusted exit signal

#### Scenario: Explicit user protection passes engineering gates
- **WHEN** a user-defined protection policy and its eligible data/delivery pass engineering acceptance and the user enables it
- **THEN** protection notifications can operate independently of directional-model accuracy or unfinished inferred-risk research
