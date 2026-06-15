## ADDED Requirements

### Requirement: Meetings SHALL create independent tracking items for follow-up work
The system SHALL allow meetings and workflow stages to create independent tracking items for unresolved questions, evidence gaps, external verification tasks, or follow-up actions that must be completed after or between discussion stages.

#### Scenario: Stage creates a follow-up item
- **WHEN** a workflow stage finishes and identifies an unresolved evidence gap
- **THEN** the system creates a tracking item linked to the meeting and the originating stage

#### Scenario: Meeting summary includes follow-up items
- **WHEN** a meeting reaches a conclusion with unresolved actions
- **THEN** the system stores those actions as tracking items rather than only embedding them in free-text summary

### Requirement: Tracking items SHALL have explicit ownership and lifecycle state
The system SHALL store each tracking item with title, description, status, owner type, optional due date, latest update, and closure metadata so that progress can be monitored over time.

#### Scenario: Tracking item is assigned
- **WHEN** a user or workflow creates a tracking item with an assignee
- **THEN** the system stores the assignee and initial status for that item

#### Scenario: Tracking item is completed
- **WHEN** a responsible actor marks a tracking item complete with supporting notes
- **THEN** the system stores the completion metadata and updates the item status

### Requirement: Tracking items SHALL support evidence and progress updates
The system SHALL allow actors to append progress notes, linked evidence, and status changes to a tracking item without mutating the original meeting transcript.

#### Scenario: Evidence is attached to tracking item
- **WHEN** a user adds a supporting document link or note to a tracking item
- **THEN** the system stores it as a tracking update associated with that item

#### Scenario: Progress update preserves history
- **WHEN** an actor changes a tracking item from in-progress to ready-for-review
- **THEN** the system records the transition and preserves the prior updates in history

### Requirement: Workflow execution SHALL be able to block on tracking conditions
The system SHALL allow workflow stages or reopen rules to declare that one or more tracking items must reach specified states before the meeting can continue to the next stage or reopen path.

#### Scenario: Workflow waits for tracking completion
- **WHEN** the next workflow transition requires a linked tracking item to be verified
- **THEN** the system keeps the workflow run in a blocked state until the item reaches the required status

#### Scenario: Partial completion does not unblock
- **WHEN** only some of the required tracking items satisfy the configured completion condition
- **THEN** the system keeps the meeting blocked and reports which items remain outstanding

### Requirement: Tracking completion SHALL be able to trigger meeting continuation
The system SHALL support configured behaviors where completion or verification of tracking items causes a meeting to resume, reopen, or activate a designated workflow stage.

#### Scenario: Verified item reopens meeting
- **WHEN** a tracking item marked as a reopen trigger reaches verified status
- **THEN** the system reactivates the meeting at the workflow stage configured for that trigger

#### Scenario: Non-trigger item stays informational
- **WHEN** a tracking item is completed but is not marked as a meeting continuation trigger
- **THEN** the system updates the tracking item without reopening or advancing the meeting

### Requirement: Meeting detail SHALL expose tracking item summaries and blockers
The system SHALL include tracking item summaries in meeting detail responses, including open counts, blocked counts, trigger items, and the latest updates needed for users to decide whether to continue the meeting.

#### Scenario: Meeting detail shows open items
- **WHEN** a client requests meeting detail for a meeting with tracking items
- **THEN** the response includes the list or summary of current tracking items and their statuses

#### Scenario: Meeting detail shows blockers
- **WHEN** the meeting is waiting on tracking work
- **THEN** the response identifies which tracking items are blocking further workflow execution
