# Workflow Map

Phase1 workflow:

`SW-01 POC Manual Pipeline`

Node chain:

1. Manual Trigger
2. Load Quote Fixture
3. Normalize Quote
4. Quote Quality Gate
5. Build Approx Bars
6. Calculate Facts
7. Build Fact Registry
8. Build Packet + Hash
9. Resolve MODEL_PROVIDER
10. MOCK Market Behavior
11. Parse Strict JSON
12. Structural Claim Validator
13. Calculate STRESS_ONLY
14. Render Markdown
15. Build Persistence Payload
16. Persist Phase1 Records
17. Phase1 Result Summary

The workflow is inactive by default and is intended for manual or CLI execution only.

The approximate bars are persisted in the packet and included in `packet.model_input` with explicit fixture-derived approximation metadata.
