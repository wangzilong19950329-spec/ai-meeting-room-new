## ADDED Requirements

### Requirement: Meeting SHALL run against an explicit workflow template
The system SHALL bind every meeting to an explicit workflow template. If the creator does not choose a template, the system SHALL assign a default built-in template that preserves the current roundtable debate behavior.

#### Scenario: Default template is assigned
- **WHEN** a user creates a meeting without specifying a workflow template
- **THEN** the system assigns the built-in default workflow template to that meeting

#### Scenario: Custom template is assigned
- **WHEN** a user creates a meeting and specifies an available workflow template
- **THEN** the system binds that template to the meeting and creates a workflow run from it

### Requirement: Workflow templates SHALL define stage structure and transitions
The system SHALL allow a workflow template to define ordered stages, each stage's execution type, allowed actor types, completion condition, and transition targets for pass, reject, or reopen actions.

#### Scenario: Template contains stage transitions
- **WHEN** an administrator defines a workflow template with analysis, review, and approval stages
- **THEN** the system stores the stage order and allowed transition targets for each stage

#### Scenario: Reject path is explicit
- **WHEN** a stage is configured to support rejection
- **THEN** the system stores the stage or state that execution returns to after rejection

### Requirement: Meetings SHALL maintain a workflow run with current stage state
The system SHALL create and maintain a workflow run instance for each meeting, including the current active stage, prior stage outcomes, stage history, and whether execution is blocked by review or follow-up conditions.

#### Scenario: Workflow run is initialized
- **WHEN** a meeting is created
- **THEN** the system creates a workflow run instance linked to that meeting

#### Scenario: Current stage advances
- **WHEN** the active stage meets its completion condition
- **THEN** the system marks that stage complete and activates the next stage defined by the workflow transition

### Requirement: Stage execution SHALL support approval, rejection, and rework
The system SHALL support stage outcomes that include completion, rejection with reason, and return-to-stage rework so that staged decision flows can iterate before final conclusion.

#### Scenario: Reviewer rejects a submission
- **WHEN** a review or approval stage is completed with a reject action
- **THEN** the system records the rejection reason and reactivates the configured rework target stage

#### Scenario: Reviewer approves a submission
- **WHEN** a review or approval stage is completed with an approve action
- **THEN** the system advances the workflow run to the configured next stage

### Requirement: Meetings SHALL expose current workflow state in detail responses
The system SHALL include workflow summary data in meeting detail responses, including template identity, current stage, current stage status, stage history summary, and available next actions for the current actor.

#### Scenario: Meeting detail shows current stage
- **WHEN** a client requests meeting detail for a workflow-backed meeting
- **THEN** the response includes the meeting's current workflow stage and stage status

#### Scenario: Meeting detail shows action affordances
- **WHEN** the current stage allows approve, reject, or submit actions
- **THEN** the response includes those actions in a machine-readable form for the client

### Requirement: Concluded meetings SHALL be reopenable through workflow rules
The system SHALL allow a concluded meeting to re-enter its workflow through a defined reopen transition so that new human follow-up or completed tracking work can continue the decision process without overwriting history.

#### Scenario: Human follow-up reopens a concluded meeting
- **WHEN** a user submits follow-up input to a concluded meeting
- **THEN** the system records a reopen event and activates the stage defined by the reopen rule

#### Scenario: Reopen preserves prior conclusion history
- **WHEN** a concluded meeting is reopened
- **THEN** the system retains the prior conclusion version and links the reopened run to that prior conclusion

### Requirement: Legacy meetings SHALL remain executable through the default workflow path
The system SHALL preserve the current debate-style meeting experience by mapping legacy or plain meetings onto the default workflow template and initializing workflow state lazily when needed.

#### Scenario: Legacy meeting is loaded
- **WHEN** the client loads a historical meeting that predates workflow templates
- **THEN** the system can derive or initialize workflow state using the default template without requiring manual migration first

#### Scenario: Legacy start behavior remains available
- **WHEN** a user starts a normal roundtable meeting
- **THEN** the system executes the built-in default workflow path rather than requiring a custom staged template
