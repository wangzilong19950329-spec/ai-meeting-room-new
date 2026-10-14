# API Discovery

Phase0 discovery is complete; Phase3 adds a narrow n8n-facing adapter rather
than exposing the existing user-session API to workflow credentials.

## Git Baseline

| Item | Value |
| --- | --- |
| Working repo | `/Users/wangzilong/IdeaProjects/ai-meeting-room-new` |
| Implementation branch | `feat/n8n-stock-watch-mvp` |
| Current HEAD | `88618af98c0e4ffe798f07f21761acca1ab76a42` |
| Prompt baseline | `eb674b736589fe8ddc716c15af3b400342286f57` |
| Decision | User accepted continuing with Phase1 on the current HEAD after preflight context. |

## Current Java API Matrix

| Endpoint | Current code path | Auth | Phase3 use |
| --- | --- | --- | --- |
| `GET /api/stock-watch/quotes?symbols=...` | `StockWatchController#getQuotes` | user `X-Token` or query token | Not used by n8n |
| `GET /api/stock-watch/intraday-bars?symbol=...&market=...` | `StockWatchController#getIntradayBars` | user `X-Token` or query token | Used internally by the adapter service |
| `GET /api/stock-watch/n8n/quotes?symbols=...` | `StockWatchN8nController` calls `StockWatchN8nFacadeService` | dedicated `X-N8N-Token` | SW-10 source |

Adapter auth evidence:

- `WebMvcConfig` excludes `/api/stock-watch/n8n/**` from user-session auth.
- `StockWatchN8nController` applies its own default-disabled, constant-time token check.
- Missing and incorrect adapter tokens return HTTP 401; disabled mode returns HTTP 503.

## Field Semantics

| Field | Current Java mapping | Phase3 status |
| --- | --- | --- |
| price | Eastmoney `f2` to `price` | Known as current quote price in existing code |
| changePercent | Eastmoney `f3` to `changePercent` | Existing code maps it; Phase1 recomputes fixture percentage from price and previous close |
| volume | Eastmoney `f5` to `volume` | Empirical A-share lot conversion; raw and normalized values retained |
| amount | Eastmoney `f6` to `amount` | Empirical CNY contract; not authoritative enough for VWAP |
| open/high/low/previousClose | Eastmoney `f17/f15/f16/f18` | Used in fixture quality checks only |
| mainInflow and related buckets | Eastmoney `f62/f66/f69/f72/f75/f78/f81/f84/f87/f184` | `UNKNOWN_SEMANTICS`; not used for intent or actor claims |
| quoteTime | latest one-minute bar `asOfTime` | Used for freshness and bar boundaries |
| providerUpdateTime | Eastmoney `f124` | Reported separately; not treated as the market observation time |

Phase3 decision:

- Java transport and authentication are enabled only through the adapter Gate.
- Stale weekend data is returned with `quality.status=STALE` and rejected by SW-10.
- `amount`, volume conversion, and money-flow actor claims are not used to create VWAP or actor intent.
