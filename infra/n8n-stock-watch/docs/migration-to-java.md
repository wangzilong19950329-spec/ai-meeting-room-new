# Java Adapter Gate

Phase3 adds:

`GET /api/stock-watch/n8n/quotes?symbols=300750.sz`

Contract:

- schema: `stock-watch.n8n.quote.v1`
- dedicated `X-N8N-Token`; no user session token or query-string credential
- disabled unless `STOCK_WATCH_N8N_ADAPTER_ENABLED=true`
- quote time comes from the latest Eastmoney one-minute bar
- provider update time is reported separately from Eastmoney `f124`
- only `sh` and `sz` are accepted by the current unit contract
- source errors, missing market time, future timestamps, and stale data fail closed

Eastmoney `f5` and `f6` are exposed as `LOT_100_SHARES` and `CNY` with
`unitContractStatus=EMPIRICAL_A_SHARE_ONLY`. This is not authoritative exchange
documentation. The n8n domain layer records the limitation and leaves VWAP
unavailable instead of treating the conversion as proven.

For a local adapter-only runtime, run `configure-java-adapter.sh` first. It sets
`AI_SCHEDULING_ENABLED=false`, preventing unrelated Spring schedulers from
running while the adapter is being tested.
