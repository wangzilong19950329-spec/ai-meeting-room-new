# Market Behavior User Prompt

Input is a `MarketFactPacket`.

Return strict JSON matching `phase1.market_behavior_view.v1`.

Required top-level fields:

- `schema_version`
- `claim_set`
- `uncertainty`
- `prohibited_capabilities_used`
- `numeric_outputs`
- `recommendation`

Every claim must include `claim_id`, `kind`, `fact_refs`, `statement`, and `confidence`.
