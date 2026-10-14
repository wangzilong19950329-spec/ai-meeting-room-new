## ADDED Requirements

### Requirement: Durable atomic risk publication
The system SHALL atomically persist the assessment, episode transition, API publication and opted-in notification obligations before transport execution.

#### Scenario: Crash after commit
- **WHEN** the process crashes after publication commit but before transport
- **THEN** the event and durable obligation can be recovered without creating another logical alert

#### Scenario: Sale or policy change wins a publication race
- **WHEN** computation used a holding or policy version superseded by a concurrent committed change
- **THEN** publication verifies the locked current position version, retains the computation as unpublished superseded input and creates no current alert or delivery obligation

### Requirement: Owner-specific recipients and transition keys
The system SHALL authorize recipient configurations per owner and deduplicate by risk domain, position generation, episode transition and recipient/channel, not merely stock code or day.

#### Scenario: Global stock delivery configuration exists
- **WHEN** a personal position warning is created
- **THEN** global stock recipients are not automatically inherited as personal-position recipients

### Requirement: Expiration and position-state cancellation
The system SHALL revalidate position generation, policy, active state, episode and expiry immediately before dispatch and cancel unsent obsolete obligations without claiming recall of in-flight messages.

#### Scenario: Full exit before queued dispatch
- **WHEN** the user records full exit before an unsent warning is dispatched
- **THEN** the warning is cancelled and remains auditable

#### Scenario: Policy changed or warning expired
- **WHEN** an obligation references an obsolete policy or expired actionable window
- **THEN** it is not sent as a current actionable warning

### Requirement: Transport uncertainty and limited retries
The system SHALL distinguish confirmed rejection, transport acceptance, unknown acceptance and user acknowledgement; uncertain SMTP outcomes SHALL NOT be blindly resent or described as exactly-once delivery.

#### Scenario: SMTP timeout after possible acceptance
- **WHEN** transport acceptance cannot be established after a timeout
- **THEN** delivery becomes UNKNOWN with a visible receipt boundary rather than a guaranteed failure retry

### Requirement: Explicit notification permission and coverage
The system SHALL require explicit enablement of new external channels and browser/voice permissions, and SHALL identify active-page, background and offline notification coverage separately.

#### Scenario: Browser notification permission denied
- **WHEN** permission is denied but the authenticated workbench is open
- **THEN** in-app risk state remains available and unavailable browser notification is not reported as delivered

### Requirement: Acknowledgement is not transport or trading success
The system SHALL separately record publication, client-reported display, transport acceptance, acknowledgement and user-reported fills with time-quality provenance.

#### Scenario: Email accepted without acknowledgement
- **WHEN** SMTP accepts a warning but no user acknowledgement exists
- **THEN** the system does not report that the user read or acted on it

### Requirement: Domain-compatible delivery rollout
The system SHALL enable new risk obligations only after legacy dispatchers are drained and all active delivery readers support domain filtering, expiry and new statuses; rollback SHALL retain a compatible reader.

#### Scenario: Older delivery binary remains active
- **WHEN** an older dispatcher cannot isolate new risk obligations
- **THEN** new risk publication to transport is fenced until compatibility is established
