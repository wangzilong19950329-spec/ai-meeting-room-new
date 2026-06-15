## 1. Domain and schema foundation

- [ ] 1.1 Define workflow domain objects for template, stage template, transition, workflow run, and stage run in the backend model layer
- [ ] 1.2 Define tracking domain objects for tracking items and tracking updates, including ownership, status, and reopen trigger metadata
- [ ] 1.3 Add MySQL schema and mapper files for workflow templates, workflow runs, stage runs, tracking items, and tracking updates
- [ ] 1.4 Seed a built-in default workflow template that preserves the current roundtable debate behavior

## 2. Workflow execution backbone

- [ ] 2.1 Refactor meeting creation to bind a workflow template and initialize a workflow run
- [ ] 2.2 Implement workflow stage activation, completion, rejection, and rework transitions in the service layer
- [ ] 2.3 Adapt the current debate-style execution path to run through the default workflow template instead of a hard-coded fixed flow
- [ ] 2.4 Add lazy workflow initialization for historical meetings that predate the new workflow tables

## 3. Tracking item lifecycle

- [ ] 3.1 Implement creation of tracking items from meeting or stage outputs with links back to meeting and originating stage
- [ ] 3.2 Implement tracking item status transitions and append-only progress or evidence updates
- [ ] 3.3 Implement workflow blocking rules based on tracking item completion conditions
- [ ] 3.4 Implement meeting continuation or reopen triggers driven by verified tracking items

## 4. API and response aggregation

- [ ] 4.1 Extend meeting detail responses to include workflow summary, current stage, available actions, and tracking summaries
- [ ] 4.2 Add APIs for workflow template selection or retrieval needed by meeting creation and detail pages
- [ ] 4.3 Add APIs for stage submissions, approval or rejection actions, and stage history retrieval
- [ ] 4.4 Add APIs for tracking item list, detail, update, status change, and evidence attachment metadata

## 5. Frontend workflow and tracking experience

- [ ] 5.1 Update meeting creation to support workflow template selection while defaulting to the built-in template
- [ ] 5.2 Update meeting detail to display current workflow stage, stage history, and stage action controls
- [ ] 5.3 Add a tracking item panel or page showing open items, blockers, trigger items, and latest updates
- [ ] 5.4 Add UI affordances for approve, reject, rework, continue meeting, and tracking update actions

## 6. Migration, testing, and rollout safety

- [ ] 6.1 Create migration scripts for production databases that add workflow and tracking tables without breaking legacy meetings
- [ ] 6.2 Add backend tests covering default workflow compatibility, staged review flows, rejection loops, and tracking-triggered reopen behavior
- [ ] 6.3 Add frontend verification for workflow state rendering and tracking interactions under active, blocked, and reopened meetings
- [ ] 6.4 Document rollout and rollback steps for enabling workflow-backed meetings in environments with existing historical data
