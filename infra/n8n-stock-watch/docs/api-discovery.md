# API Discovery

Phase0 discovery status: completed for Phase1 local POC.

## Git Baseline

| Item | Value |
| --- | --- |
| Working repo | `/Users/wangzilong/IdeaProjects/ai-meeting-room-new` |
| Implementation branch | `feat/n8n-stock-watch-mvp` |
| Current HEAD | `88618af98c0e4ffe798f07f21761acca1ab76a42` |
| Prompt baseline | `eb674b736589fe8ddc716c15af3b400342286f57` |
| Decision | User accepted continuing with Phase1 on the current HEAD after preflight context. |

## Current Java API Matrix

| Endpoint | Current code path | Auth | Phase1 use |
| --- | --- | --- | --- |
| `GET /api/stock-watch/quotes?symbols=...` | `StockWatchController#getQuotes` calls `StockQuoteService#fetchQuotes` | `X-Token` header or `token` request parameter required | Not called in Phase1 |
| `GET /api/stock-watch/intraday-bars?symbol=...&market=...` | `StockWatchController#getIntradayBars` calls `StockQuoteService#fetchIntradayBars` | `X-Token` header or `token` request parameter required | Not called in Phase1 |
| `GET /api/stock-watch/runtime-status` | `StockWatchController#runtimeStatus` | `X-Token` header or `token` request parameter required | Not called in Phase1 |

Auth evidence:

- `WebMvcConfig` applies `AuthInterceptor` to `/api/**`.
- Only `/api/auth/login` and `/api/auth/register` are excluded.
- `AuthInterceptor` reads `X-Token`, then `token`, and returns HTTP 401 when no valid token maps to a user.

## Field Semantics

| Field | Current Java mapping | Phase1 status |
| --- | --- | --- |
| price | Eastmoney `f2` to `price` | Known as current quote price in existing code |
| changePercent | Eastmoney `f3` to `changePercent` | Existing code maps it; Phase1 recomputes fixture percentage from price and previous close |
| volume | Eastmoney `f5` to `volume`; trends `parts[5]` to intraday `volume` | Existing mapping only; not used for exact liquidity claim |
| amount | Eastmoney `f6` to `amount`; trends `parts[6]` to intraday `amount` | `UNKNOWN_SEMANTICS`; not used for VWAP or money-flow claims |
| open/high/low/previousClose | Eastmoney `f17/f15/f16/f18` | Used in fixture quality checks only |
| mainInflow and related buckets | Eastmoney `f62/f66/f69/f72/f75/f78/f81/f84/f87/f184` | `UNKNOWN_SEMANTICS`; not used for intent or actor claims |
| timestamp | Eastmoney `f124` | Existing code maps it to timestamp/update time; Phase1 uses fixture timestamps |

Phase1 decision:

- The workflow runs from fixtures only.
- `amount` and `main_inflow` remain `UNKNOWN_SEMANTICS`.
- Java bridge integration is deferred to a later gate.
