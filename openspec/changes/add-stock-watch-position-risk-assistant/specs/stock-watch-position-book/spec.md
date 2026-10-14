## ADDED Requirements

### Requirement: User-owned position authorization
The system SHALL derive position ownership from authenticated currentUserId and authorize every position, event, policy, action, statistic and notification operation against that owner.

#### Scenario: Another user's identifier
- **WHEN** a user requests or modifies a position belonging to another user
- **THEN** the operation is denied without exposing position details or recipients

### Requirement: Explicit position registration
The system SHALL distinguish a watchlist holding group from registered quantity-bearing positions and SHALL require explicit activation after instrument, quantity and lot dates are validated.

#### Scenario: Importing a holding group
- **WHEN** a user imports existing holding-group stocks without quantity or purchase dates
- **THEN** the system creates drafts without invented quantities, sellability or enabled alerts

### Requirement: Lot-specific sellability
The system SHALL preserve lots, remaining quantity, reported purchase dates and source quality; estimated sellability SHALL use a verified calendar and instrument mechanism and SHALL NOT guarantee broker acceptance.

#### Scenario: Old holdings plus same-day purchase
- **WHEN** an ordinary A-share position has 1000 previously eligible shares and 500 newly bought shares today
- **THEN** the system does not label all 1500 shares as immediately sellable

#### Scenario: Unknown mechanism or broker restriction
- **WHEN** sellability cannot be established for a lot or account condition
- **THEN** the affected quantity is marked unknown or estimated rather than guaranteed available

#### Scenario: Expired manual inventory confirmation
- **WHEN** the registered inventory confirmation exceeds its agreed freshness policy
- **THEN** quantity-dependent and personal-money conclusions become stale or unavailable while eligible instrument price protection remains visible

### Requirement: Idempotent manual position events
The system SHALL use owner-scoped clientEventId and optimistic versions to atomically append manual trade or correction events and update lot balances without negative inventory.

#### Scenario: Retrying a partial sale registration
- **WHEN** the same clientEventId is submitted twice
- **THEN** the sale is recorded once and the lot quantity is reduced once

#### Scenario: Concurrent sale or policy revision
- **WHEN** an event uses a stale expectedRowVersion
- **THEN** the system returns a conflict without partially updating inventory or risk state

### Requirement: Frozen policy and holding versions
The system SHALL version policy and holding changes, preserve historical assessment inputs, and reset monitoring generation after full exit and re-entry.

#### Scenario: Late correction of purchase information
- **WHEN** purchase date or cost is corrected after an assessment was published
- **THEN** a correction and new version are stored without rewriting the prior assessment's known inputs

### Requirement: Manual assistance without order execution
The system SHALL distinguish acknowledgement, hold and plan-exit actions from user-reported fills; it SHALL NOT submit broker orders or reduce inventory from acknowledgement alone.

#### Scenario: User acknowledges a warning
- **WHEN** the user acknowledges a warning or selects plan exit
- **THEN** inventory is unchanged and no order is submitted

#### Scenario: Missing cost
- **WHEN** a valid position has no known cost
- **THEN** cost-dependent protection is unavailable while eligible price protection remains usable
