#!/usr/bin/env python3
"""Readonly descriptive M30 endpoint research, NOT strict as-of predictive accuracy."""

import argparse
from collections import Counter
import csv
from datetime import date, datetime, timedelta, timezone
import hashlib
import json
import math
import os
from pathlib import Path
import re
import subprocess
import sys


VERSION = "M30_MVP_V1"
SCOPE = "FROZEN_QUOTE_TO_TARGET"
CUTOFF = "UNVERIFIED_WEB_SEARCH"
CHINA = timezone(timedelta(hours=8))
DATABASES = ("ai_meeting",)
SUCCESS_TASK_STATUSES = {"SUCCESS", "PROJECTED_SUCCESS"}
PENDING_TASK_STATUSES = {"RESERVED", "SUBMITTED", "RESPONSE_RECEIVED"}
INPUT_STATUSES = {
    "ELIGIBLE", "UNSUPPORTED_STOCK", "OUTSIDE_CONTINUOUS_WINDOW", "QUOTE_UNAVAILABLE",
    "IDENTITY_UNVERIFIED", "PRICE_UNAVAILABLE", "SOURCE_TIME_UNAVAILABLE", "FUTURE_INPUT", "STALE_INPUT",
}
FIELDS = (
    "taskId symbol market taskStatus analysisId analysisTaskId analysisStatus "
    "version inputStatus requestStatus requestIntegrityStatus forecastStatus direction targetLow targetHigh "
    "classification evaluationScope informationCutoffStatus strictAsOfDirectionAccuracy strictAsOfDirectionAccuracyStatus "
    "asOfAt targetAt quoteSourceAt referencePrice maxQuoteAgeSeconds "
    "maxPublishDelaySeconds quoteAgeSeconds recordedAt timingProvenance "
    "recordedDelaySeconds remainingSecondsAtRecording evaluationStatus reason "
    "evaluationPriceAt endpointPrice endpointGapSeconds priceBasis barsFetchedAt "
    "frozenQuoteReturnPct absoluteFrozenQuoteReturnPct frozenQuoteDirectionHit intervalHit intervalWidthPct "
    "alwaysUpHit alwaysDownHit last30Direction last30PriceAt last30Price "
    "last30FetchedAt last30Hit"
).split()


def object_value(value):
    if isinstance(value, str):
        try:
            value = json.loads(value)
        except (ValueError, TypeError):
            return None
    return value if isinstance(value, dict) else None


def field(obj, *names):
    return next((obj[name] for name in names if name in obj), None)


def token(value, fallback="UNKNOWN"):
    return value if isinstance(value, str) and re.fullmatch(
        r"[A-Za-z0-9][A-Za-z0-9_.-]{0,63}", value
    ) else fallback


def identity(value):
    if type(value) is int and value > 0:
        return str(value)
    if isinstance(value, str) and re.fullmatch(r"[1-9][0-9]*", value):
        return value
    raise ValueError("invalid identity")


def positive(value):
    if type(value) not in (int, float):
        raise ValueError("invalid number")
    result = float(value)
    if not math.isfinite(result) or result <= 0:
        raise ValueError("invalid number")
    return result


def timestamp(value, db_proxy=False):
    if not isinstance(value, str) or ("T" not in value and not db_proxy):
        raise ValueError("invalid timestamp")
    result = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if result.tzinfo is None:
        if not db_proxy:
            raise ValueError("timestamp offset required")
        result = result.replace(tzinfo=CHINA)
    return result


def iso(value):
    return value.isoformat()


def finite_pct(price, reference):
    result = (price - reference) / reference * 100
    if not math.isfinite(result):
        raise ValueError("nonfinite percentage")
    return result


def response_object(raw):
    if isinstance(raw, str):
        start, end = raw.find("{"), raw.rfind("}")
        if start < 0 or end < start:
            return None
        raw = raw[start:end + 1]
    return object_value(raw)


def request_integrity(task, envelope):
    digest = field(task, "requestDigest", "request_digest")
    projection = envelope.get("projectionJson")
    return (isinstance(digest, str) and re.fullmatch(r"[a-f0-9]{64}", digest) is not None
            and digest == envelope.get("requestDigest") and isinstance(projection, str)
            and hashlib.sha256(projection.encode("utf-8")).hexdigest() == envelope.get("projectionDigest"))


def forecast(raw):
    root = response_object(raw)
    if root is None:
        return ("MISSING" if raw in (None, "") else "INVALID"), {}
    if "intradayForecast" not in root:
        return "MISSING", {}
    value = root["intradayForecast"]
    if not isinstance(value, dict):
        return "INVALID", {}
    if value.get("status") == "ABSTAIN":
        valid = all(key in value and value[key] is None for key in ("direction", "targetLow", "targetHigh"))
        return ("ABSTAIN" if valid else "INVALID"), {}
    try:
        low, high = positive(value.get("targetLow")), positive(value.get("targetHigh"))
        if value.get("status") != "PREDICTED" or value.get("direction") not in ("UP", "DOWN") or low > high:
            raise ValueError("invalid forecast")
        return "PREDICTED", {"direction": value["direction"], "targetLow": low, "targetHigh": high}
    except (ValueError, OverflowError):
        return "INVALID", {}


def endpoint(evidence, market, symbol, target, known_by):
    """Only qualified completed bars, never a forward shift or assumed time semantics."""
    envelopes = evidence.get(market + ":" + symbol, [])
    if isinstance(envelopes, dict):
        envelopes = [envelopes]
    if not isinstance(envelopes, list):
        return None
    candidates = []
    for envelope in envelopes:
        if not isinstance(envelope, dict) or envelope.get("timestampSemantics") != "VERIFIED_END_TIME" or envelope.get("priceBasis") != "UNADJUSTED":
            continue
        try:
            fetched = timestamp(envelope.get("fetchedAt"))
        except ValueError:
            continue
        if fetched > known_by or not isinstance(envelope.get("bars"), list):
            continue
        for bar in envelope["bars"]:
            if not isinstance(bar, dict) or bar.get("completionVerified") is not True:
                continue
            try:
                end, close = timestamp(bar.get("endTime")), positive(bar.get("close"))
            except (ValueError, OverflowError):
                continue
            if end <= fetched and 0 <= (target - end).total_seconds() < 60:
                candidates.append((end, fetched, close))
    if not candidates:
        return None
    end, fetched, close = max(candidates)
    if len({item[2] for item in candidates if item[:2] == (end, fetched)}) != 1:
        return None
    return end, fetched, close


def evaluate_task(task, envelope, request, evidence, evaluated_at):
    row = dict.fromkeys(FIELDS)
    task_id = identity(field(task, "taskId", "id", "task_id"))
    analysis = object_value(task.get("analysis")) or {
        "id": task.get("analysis_id"), "analysisTaskId": task.get("analysis_task_id"),
        "status": task.get("analysis_status"), "gmtCreate": task.get("analysis_gmt_create"),
    }
    row.update(taskId=task_id, symbol=token(task.get("symbol")), market=token(task.get("market")),
               taskStatus=token(field(task, "status", "taskStatus", "task_status")),
               analysisStatus=token(analysis.get("status"), "NO_ANALYSIS"),
               timingProvenance="DB_INSERT_PROXY", classification="DESCRIPTIVE_ONLY", evaluationScope=SCOPE,
               strictAsOfDirectionAccuracy=None, strictAsOfDirectionAccuracyStatus="NOT_ESTABLISHED")
    model_status, values = forecast(field(task, "rawResponse", "raw_response"))
    row.update(forecastStatus=model_status, **values)

    def finish(status, reason):
        row.update(evaluationStatus=status, reason=reason)
        return row

    row["requestIntegrityStatus"] = "INVALID"
    try:
        if not request_integrity(task, envelope):
            raise ValueError("invalid frozen integrity")
    except ValueError:
        row["requestStatus"] = "INVALID"
        return finish("INVALID_REQUEST", "INVALID_REQUEST_ENVELOPE_INTEGRITY")
    row["requestIntegrityStatus"] = "VERIFIED_DIGEST_BINDING"
    try:
        if analysis.get("id") is not None:
            row["analysisId"] = identity(analysis["id"])
            row["analysisTaskId"] = identity(field(analysis, "analysisTaskId", "analysis_task_id"))
        if not isinstance(request, dict) or request.get("version") != VERSION:
            raise ValueError("invalid protocol")
        if request.get("priceBasis") != "UNADJUSTED" or not isinstance(request.get("inputStatus"), str) or request["inputStatus"] not in INPUT_STATUSES:
            raise ValueError("invalid input qualification")
        if request.get("informationCutoffStatus") != CUTOFF or request.get("evaluationScope") != SCOPE:
            raise ValueError("invalid descriptive scope")
        row.update(version=VERSION, priceBasis="UNADJUSTED", inputStatus=request["inputStatus"], informationCutoffStatus=CUTOFF)
        for key, budget in (("maxQuoteAgeSeconds", 120), ("maxPublishDelaySeconds", 300)):
            if type(request.get(key)) not in (int, float) or request[key] != budget:
                raise ValueError("invalid frozen budget")
            row[key] = budget
        asof, target = (timestamp(request.get(key)) for key in ("asOfAt", "targetAt"))
        quote = timestamp(request["quoteSourceAt"]) if request.get("quoteSourceAt") is not None else None
        reference = positive(request["referencePrice"]) if request.get("referencePrice") is not None else None
        input_status = request["inputStatus"]
        if target - asof != timedelta(minutes=30):
            raise ValueError("invalid window")
        if input_status == "ELIGIBLE" and (quote is None or reference is None or quote > asof):
            raise ValueError("invalid eligible input")
        row.update(asOfAt=iso(asof), targetAt=iso(target), quoteSourceAt=iso(quote) if quote else None,
                   referencePrice=reference, inputStatus=input_status,
                   quoteAgeSeconds=(asof - quote).total_seconds() if quote else None, requestStatus="VALID")
    except (ValueError, OverflowError):
        row["requestStatus"] = "INVALID"
        return finish("INVALID_REQUEST", "INVALID_FROZEN_REQUEST_OR_LINK")
    if input_status != "ELIGIBLE":
        return finish("STALE_INPUT" if input_status == "STALE_INPUT" else "INELIGIBLE", "FROZEN_INPUT_NOT_ELIGIBLE")
    if row["quoteAgeSeconds"] > 120:
        return finish("STALE_INPUT", "QUOTE_AGE_OVER_FROZEN_BUDGET")
    if row["analysisTaskId"] is not None and row["analysisTaskId"] != task_id:
        return finish("INVALID_ANALYSIS_LINK", "ANALYSIS_NOT_LINKED_TO_TASK")
    if row["taskStatus"] not in SUCCESS_TASK_STATUSES:
        return finish("PENDING" if row["taskStatus"] in PENDING_TASK_STATUSES else "FAILED", "TASK_NOT_SUCCESSFUL")
    if row["analysisId"] is None:
        return finish("NO_ANALYSIS", "NO_LINKED_SUCCESSFUL_ANALYSIS")
    if row["analysisStatus"] != "SUCCESS":
        return finish("FAILED", "ANALYSIS_NOT_SUCCESSFUL")
    try:
        recorded = timestamp(field(analysis, "gmtCreate", "gmt_create"), db_proxy=True)
    except ValueError:
        return finish("UNKNOWN_TIMING", "MISSING_OR_INVALID_DB_INSERT_TIME")
    delay = (recorded - asof).total_seconds()
    row.update(recordedAt=iso(recorded), recordedDelaySeconds=delay,
               remainingSecondsAtRecording=(target - recorded).total_seconds())
    if delay < 0 or recorded > evaluated_at:
        return finish("INVALID_TIMING", "RECORDING_OUTSIDE_OBSERVED_TIME")
    if recorded >= target:
        return finish("EXPIRED", "RECORDED_AT_OR_AFTER_TARGET")
    if delay > 300:
        return finish("LATE", "RECORDING_DELAY_OVER_FROZEN_BUDGET")
    if model_status != "PREDICTED":
        return finish(model_status, "RAW_M30_" + model_status)
    try:
        width = (row["targetHigh"] - row["targetLow"]) / reference * 100
        if not math.isfinite(width):
            raise ValueError("nonfinite width")
        row["intervalWidthPct"] = width
    except ValueError:
        return finish("INVALID", "NONFINITE_INTERVAL_WIDTH")
    if evaluated_at < target:
        return finish("NOT_DUE", "TARGET_NOT_REACHED")
    value = endpoint(evidence, row["market"], row["symbol"], target, evaluated_at)
    if value is None:
        return finish("UNSCORABLE", "NO_QUALIFIED_ENDPOINT")
    end, fetched, close = value
    try:
        change = finite_pct(close, reference)
    except ValueError:
        return finish("UNSCORABLE", "NONFINITE_ENDPOINT_RETURN")
    row.update(evaluationPriceAt=iso(end), endpointPrice=close, barsFetchedAt=iso(fetched),
               endpointGapSeconds=(target - end).total_seconds(), priceBasis="UNADJUSTED",
               frozenQuoteReturnPct=change, absoluteFrozenQuoteReturnPct=abs(change),
               alwaysUpHit=change > 0, alwaysDownHit=change < 0,
               frozenQuoteDirectionHit=change > 0 if row["direction"] == "UP" else change < 0,
               intervalHit=row["targetLow"] <= close <= row["targetHigh"])
    historical = endpoint(evidence, row["market"], row["symbol"], asof - timedelta(minutes=30), asof)
    if historical is not None:
        old_end, old_fetched, old_close = historical
        direction = "UP" if reference > old_close else "DOWN" if reference < old_close else "FLAT"
        row.update(last30Direction=direction, last30PriceAt=iso(old_end), last30Price=old_close,
                   last30FetchedAt=iso(old_fetched))
        if direction != "FLAT":
            row["last30Hit"] = change > 0 if direction == "UP" else change < 0
    return finish("DESCRIPTIVE_ENDPOINT", "QUALIFIED_FROZEN_QUOTE_TO_TARGET_ENDPOINT")


def rate(numerator, denominator):
    return numerator / denominator if denominator else None


def summary(rows):
    endpoints = [row for row in rows if row["evaluationStatus"] == "DESCRIPTIVE_ENDPOINT"]
    trend = [row for row in endpoints if row["last30Hit"] is not None]
    timely = [row for row in rows if row["requestStatus"] == "VALID" and row["inputStatus"] == "ELIGIBLE"
              and row["quoteAgeSeconds"] <= 120 and row["taskStatus"] in SUCCESS_TASK_STATUSES
              and row["analysisTaskId"] == row["taskId"] and row["analysisStatus"] == "SUCCESS"
              and row["evaluationStatus"] not in {"INVALID_TIMING", "UNKNOWN_TIMING"}
              and row["recordedDelaySeconds"] is not None and 0 <= row["recordedDelaySeconds"] <= 300]

    def paired(cohort, include_trend=False):
        result = {"taskIds": [row["taskId"] for row in cohort], "count": len(cohort),
                  "classification": "DESCRIPTIVE_ONLY", "evaluationScope": SCOPE}
        measures = [("rawM30Direction", "frozenQuoteDirectionHit"), ("alwaysUp", "alwaysUpHit"), ("alwaysDown", "alwaysDownHit")]
        if include_trend:
            measures.append(("last30Trend", "last30Hit"))
        for name, key in measures:
            hits = sum(row[key] is True for row in cohort)
            result[name] = {"hits": hits, "frozenQuoteDirectionHitRate": rate(hits, len(cohort))}
        return result

    prediction_count = sum(row["forecastStatus"] == "PREDICTED" for row in rows)
    interval_hits = sum(row["intervalHit"] is True for row in endpoints)
    direction_hits = sum(row["frozenQuoteDirectionHit"] is True for row in endpoints)
    return {
        "requestedTaskCount": len(rows), "descriptiveEndpointCount": len(endpoints),
        "classification": "DESCRIPTIVE_ONLY", "evaluationScope": SCOPE,
        "frozenQuoteDirectionHitCount": direction_hits,
        "frozenQuoteDirectionHitRate": rate(direction_hits, len(endpoints)),
        "strictAsOfDirectionAccuracy": None, "strictAsOfDirectionAccuracyStatus": "NOT_ESTABLISHED",
        "statusCounts": dict(sorted(Counter(row["evaluationStatus"] for row in rows).items())),
        "taskStatusCounts": dict(sorted(Counter(row["taskStatus"] for row in rows).items())),
        "forecastStatusCounts": dict(sorted(Counter(row["forecastStatus"] for row in rows).items())),
        "inputStatusCounts": dict(sorted(Counter(row["inputStatus"] or "INVALID" for row in rows).items())),
        "recordingTimelyCount": len(timely), "recordingTimelyRate": rate(len(timely), len(rows)),
        "predictedCount": prediction_count, "predictedRate": rate(prediction_count, len(rows)),
        "intervalHits": interval_hits, "frozenQuoteIntervalCoverageRate": rate(interval_hits, len(endpoints)),
        "meanIntervalWidthPct": sum(row["intervalWidthPct"] / len(endpoints) for row in endpoints) if endpoints else None,
        "meanAbsoluteFrozenQuoteReturnPct": sum(row["absoluteFrozenQuoteReturnPct"] / len(endpoints) for row in endpoints) if endpoints else None,
        "pairedBaselines": paired(endpoints), "pairedLast30Trend": paired(trend, include_trend=True),
        "last30MissingOrFlatCount": len(endpoints) - len(trend),
    }


def evaluate(tasks, evidence, evaluated_at):
    if not isinstance(tasks, list) or not isinstance(evidence, dict):
        raise ValueError("invalid fixture shape")
    rows, legacy, unreadable, seen = [], 0, 0, set()
    for task in tasks:
        if not isinstance(task, dict):
            raise ValueError("invalid task shape")
        envelope = object_value(field(task, "requestEnvelope", "requestEnvelopeJson", "request_envelope_json"))
        if envelope is not None and "projectionJson" not in envelope:
            legacy += 1
            continue
        projection = object_value(envelope.get("projectionJson")) if envelope is not None else None
        if envelope is None or projection is None:
            unreadable += 1
            continue
        if "intradayForecastRequest" not in projection:
            legacy += 1
            continue
        row = evaluate_task(task, envelope, projection["intradayForecastRequest"], evidence, evaluated_at)
        if row["taskId"] in seen:
            raise ValueError("duplicate requested task identity")
        seen.add(row["taskId"])
        rows.append(row)
    rows.sort(key=lambda row: int(row["taskId"]))
    groups = {}
    for row in rows:
        day = timestamp(row["asOfAt"]).astimezone(CHINA).date().isoformat() if row["asOfAt"] else "UNKNOWN_DATE"
        key = row["market"] + ":" + row["symbol"] + ":" + day
        groups.setdefault(key, []).append(row)
    return {
        "protocol": VERSION, "evaluatedAt": iso(evaluated_at), "timingProvenance": "DB_INSERT_PROXY",
        "classification": "DESCRIPTIVE_ONLY", "evaluationScope": SCOPE, "informationCutoffStatus": CUTOFF,
        "strictAsOfDirectionAccuracy": None, "strictAsOfDirectionAccuracyStatus": "NOT_ESTABLISHED",
        "researchNotice": "Frozen quote (up to 120s old) to target description only. Neither exact starting price nor model information cutoff is verified. No formal future-30-minute predictive accuracy is established. Paired baselines are same-cohort descriptive statistics only.",
        "integrityNotice": "Task request_digest must match envelope requestDigest; projectionDigest is SHA256 of the exact projectionJson UTF-8 string. Digest binding is not authentication or proof of model information cutoff.",
        "timingNotice": "Analysis gmt_create is a recording/projection proxy, NOT user delivery. Naive DB times assumed +08:00.",
        "endpointNotice": "Verified unadjusted completed end <= target; gap <60s. No full path or drawdown. Overlapping windows are not independent.",
        "slotCoverage": {"status": "UNKNOWN", "plannedSlotCount": None, "untriggeredSlotCount": None, "coverageRate": None},
        "sourceTaskCount": len(tasks), "excludedNoM30RequestCount": legacy,
        "unreadableEnvelopeTaskCount": unreadable,
        "summary": summary(rows), "bySymbolDate": {key: summary(group) for key, group in sorted(groups.items())},
        "rows": rows,
    }


def validated_date(value):
    if not re.fullmatch(r"[0-9]{4}-[0-9]{2}-[0-9]{2}", value):
        raise ValueError("invalid date")
    date.fromisoformat(value)
    return value


def export_sql(day, database):
    validated_date(day)
    if database not in DATABASES:
        raise ValueError("database not allowlisted")
    # Choose the first successful projection, not a later replay; task is the denominator.
    return f"""SELECT JSON_OBJECT(
        'id', t.id, 'symbol', t.symbol, 'market', t.market, 'status', t.status,
        'rawResponse', t.raw_response, 'requestEnvelope', t.request_envelope_json, 'requestDigest', t.request_digest,
        'analysis', JSON_OBJECT('id', a.id, 'analysisTaskId', a.analysis_task_id,
            'status', a.status, 'gmtCreate', DATE_FORMAT(a.gmt_create, '%Y-%m-%dT%H:%i:%s')))
    FROM stock_watch_analysis_task t
    LEFT JOIN stock_watch_technical_analysis a ON a.id = (
        SELECT linked.id FROM stock_watch_technical_analysis linked
        WHERE linked.analysis_task_id = t.id AND linked.status = 'SUCCESS'
          AND linked.symbol = t.symbol AND linked.market = t.market
        ORDER BY linked.gmt_create ASC, linked.id ASC LIMIT 1)
    WHERE t.gmt_create >= '{day}' AND t.gmt_create < DATE_ADD('{day}', INTERVAL 1 DAY)
    ORDER BY t.id ASC"""


def mysql_tasks(day, database, defaults_file):
    sql = export_sql(day, database)
    command = ["mysql", "--no-defaults" if defaults_file is None else "--defaults-file=" + str(Path(defaults_file).resolve()),
               "--no-login-paths", "--init-command=", "--batch", "--raw", "--skip-column-names", "--default-character-set=utf8mb4",
               "--skip-reconnect", "--connect-timeout=10", "--database=" + database]
    environment = {key: value for key, value in os.environ.items() if key != "MYSQL_PWD"}
    result = subprocess.run(command, input=sql, capture_output=True, text=True, timeout=60, env=environment, check=False)
    if result.returncode:
        raise ValueError("mysql export failed")
    return [json.loads(line) for line in result.stdout.splitlines() if line.strip()]


def write_snapshot(output, report):
    encoded = json.dumps(report, indent=2, sort_keys=True, allow_nan=False) + "\n"
    output = Path(output)
    output.mkdir(mode=0o700, parents=False, exist_ok=False)
    for name in ("report.json", "tasks.csv"):
        path = output / name
        descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(descriptor, "w", encoding="utf-8", newline="") as stream:
            if name.endswith(".json"):
                stream.write(encoded)
            else:
                writer = csv.DictWriter(stream, fieldnames=FIELDS)
                writer.writeheader()
                writer.writerows(report["rows"])
        path.chmod(0o400)


class PrivateArgumentParser(argparse.ArgumentParser):
    def error(self, message):
        self.exit(2, "error: invalid arguments; see --help\n")


def main(argv=None):
    parser = PrivateArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter, epilog="""
Fixture: {"tasks":[{"id":1,"symbol":"600000","market":"sh","status":"PROJECTED_SUCCESS",
  "requestDigest":"<64-char lowercase SHA256>",
  "requestEnvelope":{"requestDigest":"<same task digest>","projectionDigest":"<SHA256 of projectionJson UTF-8>",
    "projectionJson":"<exact JSON string containing intradayForecastRequest>"},
  "rawResponse":{"intradayForecast":{...}},
  "analysis":{"id":2,"analysisTaskId":1,"status":"SUCCESS","gmtCreate":"...+08:00"}}]}.
requestEnvelope and rawResponse may be JSON strings. projectionJson must be the
exact frozen JSON string for byte-exact digest verification, not a reserialized object.
Snake-case export fields request_digest/request_envelope_json/raw_response/analysis_id/
analysis_task_id/analysis_status/analysis_gmt_create are also accepted. Raw model data
is the original root intradayForecast. Like the backend, first-to-last braces are
parsed inside Markdown JSON fences or surrounding short text. No envelope unwrapping.

--bars-json is input-only, no public fetch: {"sh:600000":{
  "timestampSemantics":"VERIFIED_END_TIME","priceBasis":"UNADJUSTED",
  "fetchedAt":"2026-10-05T10:31:00+08:00","bars":[{
  "endTime":"2026-10-05T10:30:00+08:00","close":10.1,"completionVerified":true}]}}.
A key may hold a list of envelopes (separate vintages). Inferred minute semantics
are rejected. Last30 trend requires a qualified endpoint at/before asOfAt-30min
(gap <60s) from a vintage fetched by asOfAt; later historical downloads cannot prove it.

Frozen request: version=M30_MVP_V1, asOfAt, targetAt=asOfAt+30min, quoteSourceAt,
referencePrice>0, priceBasis=UNADJUSTED, inputStatus=ELIGIBLE or a helper reason,
informationCutoffStatus=UNVERIFIED_WEB_SEARCH, evaluationScope=FROZEN_QUOTE_TO_TARGET,
maxQuoteAgeSeconds=120,
maxPublishDelaySeconds=300. Quote age <=120s, recording delay <=300s. No pretarget
endpoint results, zero return never hits. First successful linked analysis gmt_create is
DB_INSERT_PROXY, NOT user delivery; naive DB times assumed +08:00. Trading-session
eligibility is frozen upstream, not independently reconstructed here. Ineligible
helper requests may have null quoteSourceAt/referencePrice and remain in the report.
Every row is DESCRIPTIVE_ONLY. frozenQuoteReturnPct/frozenQuoteDirectionHitRate
describe the frozen quote to target, NOT strict future-30-minute accuracy.
strictAsOfDirectionAccuracy is always null/NOT_ESTABLISHED: web-search cutoff and
the exact starting price are not verified. Paired baselines are descriptive only.

--date exports tasks by task.gmt_create calendar date (DB local time), with one
SELECT and a LEFT JOIN, including failures/no analysis. Database allowlist: ai_meeting.
Credentials only through the protected defaults file, never password arguments;
MYSQL_PWD is ignored. Output parent must exist; output directory must NOT exist.
Only whitelisted research fields are written, never raw responses or envelopes.
Unknown planned-slot coverage stays unknown. No DB writes, model calls or path drawdown.
""")
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--input", help="Fixture JSON object containing a tasks array")
    source.add_argument("--date", help="Task creation date YYYY-MM-DD for read-only mysql export")
    parser.add_argument("--database", default="ai_meeting", choices=DATABASES)
    parser.add_argument("--mysql-defaults-file", help="Protected MySQL defaults file; no password arguments")
    parser.add_argument("--bars-json", help="Qualified per-market:symbol evidence JSON; omitted means unscorable")
    parser.add_argument("--output", required=True, help="New immutable research snapshot directory")
    args = parser.parse_args(argv)
    try:
        if os.path.lexists(args.output):
            raise ValueError("output exists")
        if args.input and (args.mysql_defaults_file or args.database != "ai_meeting"):
            raise ValueError("mysql options require date")
        if args.input:
            with open(args.input, encoding="utf-8") as stream:
                tasks = json.load(stream)["tasks"]
        else:
            tasks = mysql_tasks(args.date, args.database, args.mysql_defaults_file)
        evidence = {}
        if args.bars_json:
            with open(args.bars_json, encoding="utf-8") as stream:
                evidence = json.load(stream)
        report = evaluate(tasks, evidence, datetime.now(timezone.utc))
        report["sourceSelection"] = "FIXTURE" if args.input else "TASK_DB_CREATE_DATE"
        report["exportDate"] = args.date
        write_snapshot(args.output, report)
    except (OSError, ValueError, TypeError, KeyError, OverflowError, subprocess.SubprocessError):
        print("error: invalid input, export failed, or output unavailable; no overwrite attempted", file=sys.stderr)
        return 2
    print("M30 descriptive snapshot created: requested={requestedTaskCount} endpoints={descriptiveEndpointCount}".format(**report["summary"]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
