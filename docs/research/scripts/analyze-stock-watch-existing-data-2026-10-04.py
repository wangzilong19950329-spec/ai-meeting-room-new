"""Read-only frozen-database research; no model calls, backfill, or trading simulation."""

import argparse
import collections
import datetime as dt
import hashlib
import json
import math
import pathlib
import re
import statistics
import subprocess
from zoneinfo import ZoneInfo


def parse_json(value):
    if isinstance(value, dict):
        return value
    if not isinstance(value, str):
        return {}
    try:
        result = json.loads(value)
        return result if isinstance(result, dict) else {}
    except (ValueError, TypeError):
        decoder = json.JSONDecoder()
        for index, char in enumerate(value):
            if char == "{":
                try:
                    result, _ = decoder.raw_decode(value[index:])
                    if isinstance(result, dict) and any(k in result for k in ("forecasts", "mainForceIntent", "conclusion")):
                        return result
                except ValueError:
                    pass
    return {}


def number(value):
    try:
        result = float(value)
        return result if math.isfinite(result) else None
    except (ValueError, TypeError):
        return None


def wilson(hits, n):
    if not n:
        return None
    z = 1.959963984540054
    p = hits / n
    denominator = 1 + z * z / n
    center = (p + z * z / (2 * n)) / denominator
    radius = z * math.sqrt(p * (1 - p) / n + z * z / (4 * n * n)) / denominator
    return [round(100 * (center - radius), 3), round(100 * (center + radius), 3)]


def rate(hits, n):
    return {"hits": hits, "n": n, "pct": round(100 * hits / n, 3) if n else None}


def classified(ret):
    return "UP" if ret >= 1 else "DOWN" if ret <= -1 else "SIDEWAYS"


def mean(values):
    return round(statistics.mean(values), 4) if values else None


def summary(rows):
    rows = [r for r in rows if r["status"] == "EVALUATED" and r["ret"] is not None]
    n = len(rows)
    directional = [r for r in rows if r["direction"] in ("UP", "DOWN")]
    sign_hit = sum((r["direction"] == "UP" and r["ret"] >= 0) or
                   (r["direction"] == "DOWN" and r["ret"] <= 0) for r in directional)
    strict_sign_hit = sum((r["direction"] == "UP" and r["ret"] > 0) or
                          (r["direction"] == "DOWN" and r["ret"] < 0) for r in directional)
    price_baseline = [r for r in rows if r["daily"] is not None]
    flow_baseline = [r for r in rows if r["flow"] is not None]
    lows = [r for r in rows if r["low_pct"] is not None]
    invalidation_rows = [r for r in rows if r["invalidated"] is not None]
    return {
        "n": n, "dates": len({r["forecast_date"] for r in rows}),
        "stocks": len({(r["symbol"], r["market"]) for r in rows}),
        "direction_counts": dict(collections.Counter(r["direction"] for r in rows)),
        "direction_threshold_hit": rate(sum(bool(r["direction_hit"]) for r in rows), n),
        "nondivergence_return_sign": rate(sign_hit, len(directional)),
        "strict_sign_hit": rate(strict_sign_hit, len(directional)),
        "zero_directional_returns": sum(r["ret"] == 0 for r in directional),
        "sign_wilson_descriptive_only": wilson(sign_hit, len(directional)),
        "actual_direction_counts": dict(collections.Counter(classified(r["ret"]) for r in rows)),
        "always_up_sign_hit": rate(sum(r["ret"] >= 0 for r in rows), n),
        "always_down_sign_hit": rate(sum(r["ret"] <= 0 for r in rows), n),
        "source_price_direction_threshold_hit": rate(sum(classified(r["daily"]) == classified(r["ret"]) for r in price_baseline), len(price_baseline)),
        "source_flow_direction_threshold_hit": rate(sum(("UP" if r["flow"] > 0 else "DOWN" if r["flow"] < 0 else "SIDEWAYS") == classified(r["ret"]) for r in flow_baseline), len(flow_baseline)),
        "range_close_hit": rate(sum(bool(r["range_close_hit"]) for r in rows), n),
        "range_touch_hit": rate(sum(bool(r["range_touch_hit"]) for r in rows), n),
        "invalidated": rate(sum(bool(r["invalidated"]) for r in invalidation_rows), len(invalidation_rows)),
        "mean_return_pct": mean([r["ret"] for r in rows]),
        "median_return_pct": round(statistics.median([r["ret"] for r in rows]), 4) if n else None,
        "minimum_price_below_source_5pct": rate(sum(r["low_pct"] <= -5 for r in lows), len(lows)),
        "mean_low_vs_source_pct": mean([r["low_pct"] for r in lows]),
        "mean_probability": mean([r["probability"] for r in rows if r["probability"] is not None]),
        "mean_brier_score": mean([r["brier_score"] for r in rows if r["brier_score"] is not None]),
    }


def grouped(rows, key):
    groups = collections.defaultdict(list)
    for row in rows:
        groups[str(key(row))].append(row)
    return {k: summary(v) for k, v in sorted(groups.items())}


def canonical(rows):
    first = {}
    for row in sorted(rows, key=lambda r: (r["gmt_create"], r["id"])):
        key = (row["symbol"], row["market"], row["forecast_date"], row["horizon_code"])
        first.setdefault(key, row)
    return list(first.values())


def completed_bar_catalog(analyses):
    catalog = {}
    for analysis in sorted(analyses.values(), key=lambda a: (a["gmt_create"], a["id"])):
        snapshot = analysis["snapshot"]
        quote = snapshot.get("quote") or {}
        timestamp = number(quote.get("timestamp"))
        if timestamp is None:
            continue
        source_at = dt.datetime.fromtimestamp(timestamp, ZoneInfo("Asia/Shanghai"))
        source_date = source_at.date().isoformat()
        close_hour = 16 if analysis["market"] == "hk" else 15
        bars = [b for b in snapshot.get("dailyKline", [])
                if isinstance(b, dict) and isinstance(b.get("date"), str)
                and (b["date"] < source_date or (b["date"] == source_date and source_at.hour >= close_hour))]
        bars = sorted(bars, key=lambda b: b["date"])
        if not bars:
            continue
        key = (analysis["symbol"], analysis["market"])
        existing = catalog.get(key)
        if existing is None or (bars[-1]["date"], analysis["gmt_create"], analysis["id"]) > existing["rank"]:
            catalog[key] = {"bars": bars, "analysis_id": analysis["id"],
                            "rank": (bars[-1]["date"], analysis["gmt_create"], analysis["id"])}
    return catalog


def reprice(row, analysis, catalog):
    source = number(row.get("source_price"))
    snapshot = analysis.get("snapshot", {})
    quote = snapshot.get("quote") or {}
    previous_close = number(quote.get("previousClose"))
    timestamp = number(quote.get("timestamp"))
    dataset = catalog.get((row["symbol"], row["market"]))
    if not source or source <= 0 or not previous_close or previous_close <= 0 or not dataset or timestamp is None:
        return None
    quote_date = dt.datetime.fromtimestamp(timestamp, ZoneInfo("Asia/Shanghai")).date().isoformat()
    if quote_date != row["forecast_date"]:
        return None
    past = [b for b in dataset["bars"] if b["date"] < row["forecast_date"]]
    future = [b for b in dataset["bars"] if b["date"] > row["forecast_date"]]
    days = row["horizon_trading_days"]
    if not past or not days or len(future) < days:
        return None
    prior_adjusted_close = number(past[-1].get("close"))
    if not prior_adjusted_close or prior_adjusted_close <= 0:
        return None
    scale = previous_close / prior_adjusted_close
    window = future[:days]
    close = number(window[-1].get("close"))
    lows = [number(b.get("low")) for b in window]
    highs = [number(b.get("high")) for b in window]
    if close is None or any(v is None for v in lows + highs):
        return None
    close *= scale
    low = min(lows) * scale
    high = max(highs) * scale
    ret = (close / source - 1) * 100
    result = dict(row)
    result.update(status="EVALUATED", ret=ret, low_pct=100 * (low / source - 1),
                  scale=scale, outcome_bar_analysis_id=dataset["analysis_id"],
                  rebuilt_trade_date=window[-1]["date"], original_status=row.get("status"),
                  direction_hit=classified(ret) == row["direction"],
                  actual_close=close, actual_low=low, actual_high=high, invalidated=None)
    target_low, target_high = number(row.get("target_low")), number(row.get("target_high"))
    result["range_close_hit"] = target_low is not None and target_high is not None and target_low <= close <= target_high
    result["range_touch_hit"] = target_low is not None and target_high is not None and high >= target_low and low <= target_high
    if result.get("probability") is not None:
        result["brier_score"] = (result["probability"] - result["direction_hit"]) ** 2
    return result


def export_snapshot(path):
    prediction_fields = "id analysis_id symbol market stock_name horizon_code horizon_trading_days forecast_date source_price direction probability target_low target_high status evaluated_trade_date actual_close actual_high actual_low actual_return_pct direction_hit range_close_hit range_touch_hit invalidated brier_score direction_policy_version gmt_create evaluated_at".split()
    analysis_fields = "id symbol market stock_name analysis_date period_type run_slot status stance confidence conclusion evidence_json raw_response main_force_intent_json main_force_intent_status data_snapshot_json gmt_create".split()
    queries = ["SET SESSION TRANSACTION ISOLATION LEVEL REPEATABLE READ", "START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY"]
    for kind, table, fields, where in (
            ("prediction", "stock_watch_prediction", prediction_fields, "1=1"),
            ("analysis", "stock_watch_technical_analysis", analysis_fields, "status='SUCCESS'")):
        pairs = ["'_kind'", "'" + kind + "'"]
        for field in fields:
            pairs.extend(["'" + field + "'", field])
        queries.append("SELECT JSON_OBJECT(" + ",".join(pairs) + ") FROM " + table + " WHERE " + where + " ORDER BY id")
    queries.append("COMMIT")
    with path.open("wb") as output:
        subprocess.run(["mysql", "-h", "127.0.0.1", "-u", "root", "ai_meeting", "--batch", "--raw", "--skip-column-names", "--execute", ";".join(queries)], stdout=output, check=True)


def analyze(path):
    data = [json.loads(line) for line in path.read_text().splitlines()]
    analyses = {r["id"]: r for r in data if r["_kind"] == "analysis"}
    predictions = [r for r in data if r["_kind"] == "prediction"]
    for row in list(analyses.values()) + predictions:
        row["market"] = row["market"].strip().lower()
        row["symbol"] = row["symbol"].strip().lower()
    input_keys = collections.Counter()
    parsed_raw = 0
    for analysis in analyses.values():
        snapshot = parse_json(analysis["data_snapshot_json"])
        raw = parse_json(analysis["raw_response"])
        parsed_raw += bool(raw)
        model_intent = raw.get("mainForceIntent") or {}
        if not isinstance(model_intent, dict):
            model_intent = {}
        analysis["snapshot"] = snapshot
        analysis["model_raw"] = raw
        analysis["model_intent"] = model_intent.get("positioningIntent", model_intent.get("intent", "MISSING"))
        profile = parse_json(analysis["main_force_intent_json"])
        analysis["server_intent"] = profile.get("positioningIntent", profile.get("intent", "MISSING"))
        input_keys.update((snapshot.get("technicalIndicators") or {}).keys())
    for prediction in predictions:
        analysis = analyses.get(prediction["analysis_id"], {})
        snapshot = analysis.get("snapshot", {})
        quote = snapshot.get("quote") or {}
        prediction["daily"] = number(quote.get("changePercent"))
        prediction["flow"] = number(quote.get("mainInflowRatio"))
        prediction["ret"] = number(prediction["actual_return_pct"])
        source = number(prediction["source_price"])
        low = number(prediction["actual_low"])
        prediction["low_pct"] = 100 * (low / source - 1) if low is not None and source and source > 0 else None
        if prediction["ret"] is None and source and source > 0:
            close = number(prediction["actual_close"])
            if close is not None:
                prediction["ret"] = 100 * (close / source - 1)
        prediction["probability"] = number(prediction["probability"])
        prediction["brier_score"] = number(prediction["brier_score"])
        prediction["model_intent"] = analysis.get("model_intent", "MISSING")
        prediction["server_intent"] = analysis.get("server_intent", "MISSING")
        prediction["period_type"] = analysis.get("period_type", "MISSING")
        prediction["source_date"] = snapshot.get("sourceTradeDate", prediction["forecast_date"])
        prediction["source_timely"] = prediction["gmt_create"][:10] <= prediction["forecast_date"]
        prediction["timely"] = bool(analysis) and prediction["source_timely"]
        prediction["price_flow_combo"] = ("PRICE_UP" if prediction["daily"] is not None and prediction["daily"] > 0 else "PRICE_DOWN" if prediction["daily"] is not None and prediction["daily"] < 0 else "PRICE_FLAT_OR_MISSING") + "_" + ("FLOW_IN" if prediction["flow"] is not None and prediction["flow"] > 0 else "FLOW_OUT" if prediction["flow"] is not None and prediction["flow"] < 0 else "FLOW_FLAT_OR_MISSING")
    eligible = [r for r in predictions if r["timely"] and r["period_type"] == "DAY"]
    dedup = canonical(eligible)
    t1 = [r for r in dedup if r["horizon_code"] == "T1"]
    by_direction = grouped(t1, lambda r: r["direction"])
    pairs = [r for r in t1 if r["status"] == "EVALUATED" and r["ret"] is not None and r["daily"] is not None and r["flow"] is not None and r["daily"] != 0 and r["flow"] != 0]
    daily_model = [r for r in t1 if r["direction"] != "SIDEWAYS" and r["daily"] is not None and r["daily"] != 0]
    flow_model = [r for r in t1 if r["direction"] != "SIDEWAYS" and r["flow"] is not None and r["flow"] != 0]
    result = {
        "snapshot_sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
        "snapshot_path": str(path), "prediction_count": len(predictions), "successful_analysis_count": len(analyses),
        "prediction_status": dict(collections.Counter(r["status"] for r in predictions)),
        "prediction_date_range": [min(r["forecast_date"] for r in predictions), max(r["forecast_date"] for r in predictions)],
        "analysis_date_range": [min(r["analysis_date"] for r in analyses.values()), max(r["analysis_date"] for r in analyses.values())],
        "parsed_raw_response": parsed_raw,
        "late_prediction_rows": sum(not r["source_timely"] for r in predictions),
        "missing_analysis_rows": sum(r["analysis_id"] not in analyses for r in predictions),
        "all_evaluated_by_horizon": grouped(predictions, lambda r: r["horizon_code"]),
        "all_canonical_by_horizon": grouped(canonical(predictions), lambda r: r["horizon_code"]),
        "timely_day_canonical_by_horizon": grouped(dedup, lambda r: r["horizon_code"]),
        "timely_t1_by_direction": by_direction,
        "timely_t1_by_probability": grouped(t1, lambda r: "MISSING" if r["probability"] is None else "<0.60" if r["probability"] < 0.60 else "0.60-0.69" if r["probability"] < 0.70 else ">=0.70"),
        "timely_t1_by_model_intent": grouped(t1, lambda r: r["model_intent"]),
        "timely_t1_by_server_intent": grouped(t1, lambda r: r["server_intent"]),
        "timely_t1_by_combo": grouped(t1, lambda r: r["price_flow_combo"]),
        "timely_t1_by_stock": grouped(t1, lambda r: r["stock_name"] + "." + r["market"]),
        "timely_t1_by_month": grouped(t1, lambda r: r["forecast_date"][:7]),
        "timely_t1_by_day": grouped(t1, lambda r: r["forecast_date"]),
        "contemporaneous_price_flow_same_sign": rate(sum((r["daily"] > 0) == (r["flow"] > 0) for r in pairs), len(pairs)),
        "model_direction_price_same_sign": rate(sum((r["direction"] == "UP") == (r["daily"] > 0) for r in daily_model), len(daily_model)),
        "model_direction_flow_same_sign": rate(sum((r["direction"] == "UP") == (r["flow"] > 0) for r in flow_model), len(flow_model)),
        "technical_indicator_keys": dict(input_keys),
        "boundaries": ["Exploratory historical associations, not unseen validation.", "Same-stock same-date same-horizon retains earliest eligible generated forecast before filtering mature outcomes.", "Timely requires prediction generation date <= forecast date; post-close signals remain and entry is not executable price.", "Minimum future daily-bar low relative to source price is not peak-to-trough drawdown or executable avoided loss.", "Wilson intervals are descriptive only; dates/stocks are correlated.", "No returns corrected here for adjustment, costs, fills, dividends, or benchmark exposure."]
    }
    catalog = completed_bar_catalog(analyses)
    rebuilt = [value for r in dedup if (value := reprice(r, analyses[r["analysis_id"]], catalog)) is not None]
    rebuilt_t1 = [r for r in rebuilt if r["horizon_code"] == "T1"]
    matched = [r for r in rebuilt if r["original_status"] == "EVALUATED"]
    original_by_id = {r["id"]: r for r in predictions}
    big_difference = [r for r in matched if original_by_id[r["id"]]["ret"] is not None and
                      abs(r["ret"] - original_by_id[r["id"]]["ret"]) >= 1]
    sign_changed = [r for r in matched if original_by_id[r["id"]]["ret"] is not None and
                    (r["ret"] > 0) != (original_by_id[r["id"]]["ret"] > 0)]
    def brief(r):
        return {k: r.get(k) for k in ("id", "analysis_id", "symbol", "market", "stock_name", "forecast_date", "horizon_code", "direction", "ret", "low_pct", "scale", "outcome_bar_analysis_id", "rebuilt_trade_date")}
    probability_group = lambda r: "MISSING" if r["probability"] is None else "<0.60" if r["probability"] < 0.60 else "0.60-0.69" if r["probability"] < 0.70 else ">=0.70"
    result.update(
        snapshot_captured_at=dt.datetime.fromtimestamp(path.stat().st_mtime, ZoneInfo("Asia/Shanghai")).isoformat(),
        completed_bar_catalog={k[0] + "." + k[1]: {"analysis_id": v["analysis_id"], "last_completed_date": v["bars"][-1]["date"], "bar_count": len(v["bars"])} for k, v in catalog.items()},
        rebuilt_by_horizon=grouped(rebuilt, lambda r: r["horizon_code"]),
        rebuilt_t1_by_direction=grouped(rebuilt_t1, lambda r: r["direction"]),
        rebuilt_t1_directional_same_sample=summary([r for r in rebuilt_t1 if r["direction"] in ("UP", "DOWN")]),
        rebuilt_t5_directional_same_sample=summary([r for r in rebuilt if r["horizon_code"] == "T5" and r["direction"] in ("UP", "DOWN")]),
        rebuilt_t1_by_probability=grouped(rebuilt_t1, probability_group),
        rebuilt_t1_by_model_intent=grouped(rebuilt_t1, lambda r: r["model_intent"]),
        rebuilt_t1_by_combo=grouped(rebuilt_t1, lambda r: r["price_flow_combo"]),
        rebuilt_t1_by_month=grouped(rebuilt_t1, lambda r: r["forecast_date"][:7]),
        rebuilt_t1_by_stock=grouped(rebuilt_t1, lambda r: r["symbol"] + "." + r["market"]),
        repricing_comparison={"matched_evaluated": len(matched), "reconstructed_pending": sum(r["original_status"] == "PENDING" for r in rebuilt), "difference_at_least_1pp": len(big_difference), "sign_changed": len(sign_changed), "examples": [dict(brief(r), stored_return=original_by_id[r["id"]]["ret"]) for r in sorted(big_difference, key=lambda r: abs(r["ret"] - original_by_id[r["id"]]["ret"]), reverse=True)[:8]]},
        rebuilt_t1_examples=[brief(r) for r in rebuilt_t1],
        strong_word_candidates=[])
    result["canonical_timely_day_total_by_horizon"] = dict(collections.Counter(r["horizon_code"] for r in dedup))
    last_by_key = {}
    for row in sorted(eligible, key=lambda r: (r["gmt_create"], r["id"])):
        last_by_key[(row["symbol"], row["market"], row["forecast_date"], row["horizon_code"])] = row
    last_rebuilt = [value for r in last_by_key.values()
                    if (value := reprice(r, analyses[r["analysis_id"]], catalog)) is not None]
    result["last_forecast_sensitivity_directional"] = grouped(
        [r for r in last_rebuilt if r["direction"] in ("UP", "DOWN") and r["horizon_code"] in ("T1", "T5")],
        lambda r: r["horizon_code"])
    result["cn_equity_t1_directional_sensitivity"] = summary([
        r for r in rebuilt_t1 if r["direction"] in ("UP", "DOWN") and r["market"] != "hk"
        and not r["symbol"].startswith(("15", "51", "58"))])
    premature = [r for r in predictions if r["status"] == "EVALUATED" and r["evaluated_at"]
                 and r["evaluated_trade_date"] and r["evaluated_at"][:10] == r["evaluated_trade_date"]
                 and r["evaluated_at"][11:16] < ("16:00" if r["market"] == "hk" else "15:00")]
    result["evaluated_before_target_day_close"] = {"n": len(premature), "total_evaluated": sum(r["status"] == "EVALUATED" for r in predictions), "examples": [{k: r[k] for k in ("id", "symbol", "market", "forecast_date", "horizon_code", "evaluated_trade_date", "evaluated_at")} for r in premature[:5]]}
    decision_rows = [r for r in rebuilt_t1 if r["direction"] in ("UP", "DOWN")]
    price_decisions = [r for r in decision_rows if r["daily"] is not None and r["daily"] != 0]
    flow_decisions = [r for r in decision_rows if r["flow"] is not None and r["flow"] != 0]
    result["rebuilt_t1_model_direction_price_same_sign"] = rate(sum((r["direction"] == "UP") == (r["daily"] > 0) for r in price_decisions), len(price_decisions))
    result["rebuilt_t1_model_direction_flow_same_sign"] = rate(sum((r["direction"] == "UP") == (r["flow"] > 0) for r in flow_decisions), len(flow_decisions))
    expression = re.compile(r"典型[^。；，\n]{0,8}(?:派发|出货)|出货意愿明确|大单砸盘出货格局")
    by_analysis_t1 = {}
    for r in predictions:
        if r["horizon_code"] == "T1":
            by_analysis_t1.setdefault(r["analysis_id"], r)
    for a in analyses.values():
        if a["period_type"] != "DAY":
            continue
        raw = a["model_raw"]
        model_text = json.dumps({k: v for k, v in raw.items() if k != "forecasts"}, ensure_ascii=False) if raw else a["raw_response"] or ""
        snippets = [model_text[max(0, m.start() - 55): m.end() + 100] for m in expression.finditer(model_text)]
        if snippets:
            p = by_analysis_t1.get(a["id"])
            repriced = reprice(p, a, catalog) if p else None
            result["strong_word_candidates"].append({"analysis_id": a["id"], "symbol": a["symbol"], "market": a["market"], "date": a["analysis_date"], "created": a["gmt_create"], "snippets": snippets, "outcome": brief(repriced) if repriced else None})
    return result, predictions, analyses, t1


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--snapshot", type=pathlib.Path, required=True)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    parser.add_argument("--export", action="store_true")
    args = parser.parse_args()
    if args.export:
        export_snapshot(args.snapshot)
    result, _, _, _ = analyze(args.snapshot)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps({key: result[key] for key in ("prediction_count", "successful_analysis_count", "prediction_status", "late_prediction_rows", "missing_analysis_rows", "rebuilt_by_horizon", "rebuilt_t1_directional_same_sample", "repricing_comparison")}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
