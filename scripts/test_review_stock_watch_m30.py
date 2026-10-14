"""Synthetic-only stdlib tests; no MySQL, services, credentials or network."""

import contextlib
import csv
from datetime import datetime, timedelta, timezone
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import re
import tempfile
import unittest
from unittest.mock import patch


SPEC = importlib.util.spec_from_file_location("review_stock_watch_m30", Path(__file__).with_name("review-stock-watch-m30.py"))
review = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(review)
ASOF = datetime(2020, 1, 2, 10, tzinfo=review.CHINA)
TARGET = ASOF + timedelta(minutes=30)
NOW = TARGET + timedelta(minutes=2)


def task(task_id=1):
    value = {
        "id": task_id, "symbol": "600000", "market": "sh", "status": "PROJECTED_SUCCESS",
        "requestEnvelope": {"projectionJson": {"intradayForecastRequest": {
            "version": review.VERSION, "asOfAt": ASOF.isoformat(), "targetAt": TARGET.isoformat(),
            "quoteSourceAt": (ASOF - timedelta(seconds=120)).isoformat(), "referencePrice": 10,
            "priceBasis": "UNADJUSTED", "inputStatus": "ELIGIBLE", "maxQuoteAgeSeconds": 120, "maxPublishDelaySeconds": 300,
            "informationCutoffStatus": "UNVERIFIED_WEB_SEARCH", "evaluationScope": "FROZEN_QUOTE_TO_TARGET",
        }}},
        "rawResponse": {"intradayForecast": {"status": "PREDICTED", "direction": "UP", "targetLow": 10, "targetHigh": 11}},
        "analysis": {"id": task_id + 100, "analysisTaskId": task_id, "status": "SUCCESS",
                     "gmtCreate": (ASOF + timedelta(seconds=300)).isoformat()},
    }
    set_projection(value, value["requestEnvelope"]["projectionJson"])
    return value


def set_projection(value, projection):
    text = json.dumps(projection, separators=(",", ":"), ensure_ascii=False)
    digest = hashlib.sha256(("synthetic-stock-analysis-response-v6\n" + text).encode("utf-8")).hexdigest()
    value["requestDigest"] = digest
    value["requestEnvelope"].update(requestDigest=digest, projectionJson=text,
                                    projectionDigest=hashlib.sha256(text.encode("utf-8")).hexdigest())


def abstain():
    return {"status": "ABSTAIN", "direction": None, "targetLow": None, "targetHigh": None}


def request(value):
    return json.loads(value["requestEnvelope"]["projectionJson"])["intradayForecastRequest"]


def update_request(value, **changes):
    projection = json.loads(value["requestEnvelope"]["projectionJson"])
    projection["intradayForecastRequest"].update(changes)
    set_projection(value, projection)


def envelope(end=TARGET, price=10.5, fetched=NOW):
    return {"timestampSemantics": "VERIFIED_END_TIME", "priceBasis": "UNADJUSTED",
            "fetchedAt": fetched.isoformat(), "bars": [{"endTime": end.isoformat(), "close": price, "completionVerified": True}]}


def bars(end=TARGET, price=10.5):
    return {"sh:600000": envelope(end, price)}


def evaluate(value=None, evidence=None, now=NOW):
    return review.evaluate([task() if value is None else value], bars() if evidence is None else evidence, now)


class QualificationTests(unittest.TestCase):
    def test_descriptive_results_cannot_be_promoted_to_strict_accuracy(self):
        value = task()
        update_request(value, strictAsOfDirectionAccuracy=1)
        report = evaluate(value)
        self.assertEqual(report["summary"]["frozenQuoteDirectionHitRate"], 1)
        for result in (report, report["summary"], report["rows"][0], *report["bySymbolDate"].values()):
            self.assertEqual(result["classification"], "DESCRIPTIVE_ONLY")
            self.assertEqual(result["evaluationScope"], "FROZEN_QUOTE_TO_TARGET")
            self.assertIsNone(result["strictAsOfDirectionAccuracy"])
            self.assertEqual(result["strictAsOfDirectionAccuracyStatus"], "NOT_ESTABLISHED")
        self.assertEqual(report["informationCutoffStatus"], "UNVERIFIED_WEB_SEARCH")
        for result in (report["summary"]["pairedBaselines"], report["summary"]["pairedLast30Trend"]):
            self.assertEqual(result["classification"], "DESCRIPTIVE_ONLY")
        self.assertNotIn("returnPct", report["rows"][0])
        self.assertNotIn("directionHit", report["rows"][0])
        self.assertNotIn("scoredTaskCount", report["summary"])
        for key, changed in (("informationCutoffStatus", "VERIFIED"), ("informationCutoffStatus", None),
                             ("evaluationScope", "STRICT_ASOF"), ("evaluationScope", None)):
            value = task()
            update_request(value, **{key: changed})
            row = evaluate(value)["rows"][0]
            self.assertEqual(row["evaluationStatus"], "INVALID_REQUEST")
            self.assertIsNone(row["strictAsOfDirectionAccuracy"])

    def test_exact_endpoint_and_inclusive_quote_publish_budgets(self):
        report = evaluate()
        row = report["rows"][0]
        self.assertEqual(row["evaluationStatus"], "DESCRIPTIVE_ENDPOINT")
        self.assertEqual(row["quoteAgeSeconds"], 120)
        self.assertEqual(row["recordedDelaySeconds"], 300)
        self.assertEqual(row["remainingSecondsAtRecording"], 1500)
        self.assertEqual(row["endpointGapSeconds"], 0)
        self.assertAlmostEqual(row["frozenQuoteReturnPct"], 5)
        self.assertTrue(row["frozenQuoteDirectionHit"])
        self.assertTrue(row["intervalHit"])
        self.assertEqual(row["timingProvenance"], "DB_INSERT_PROXY")
        self.assertEqual(row["requestIntegrityStatus"], "VERIFIED_DIGEST_BINDING")
        self.assertEqual(report["summary"]["recordingTimelyCount"], 1)

    def test_endpoint_gap_strict_less_than_60_and_no_forward_shift(self):
        for seconds, expected in ((0, "DESCRIPTIVE_ENDPOINT"), (59.999, "DESCRIPTIVE_ENDPOINT"), (60, "UNSCORABLE"), (-0.001, "UNSCORABLE")):
            with self.subTest(seconds=seconds):
                row = evaluate(evidence=bars(TARGET - timedelta(seconds=seconds)))["rows"][0]
                self.assertEqual(row["evaluationStatus"], expected)
        evidence = bars(TARGET - timedelta(seconds=59), 10.2)
        evidence["sh:600000"]["bars"].append(envelope(TARGET + timedelta(seconds=1), 99)["bars"][0])
        self.assertEqual(evaluate(evidence=evidence)["rows"][0]["endpointPrice"], 10.2)

    def test_last_completed_qualified_bar(self):
        evidence = bars(TARGET - timedelta(seconds=59), 10.2)
        incomplete = envelope(TARGET, 99)["bars"][0]
        incomplete["completionVerified"] = False
        evidence["sh:600000"]["bars"].append(incomplete)
        self.assertEqual(evaluate(evidence=evidence)["rows"][0]["endpointPrice"], 10.2)

    def test_no_scores_before_target_even_with_completed_near_endpoint(self):
        before = TARGET - timedelta(microseconds=1)
        evidence = {"sh:600000": envelope(before, 10.5, before)}
        row = evaluate(evidence=evidence, now=before)["rows"][0]
        self.assertEqual(row["evaluationStatus"], "NOT_DUE")
        for key in ("frozenQuoteReturnPct", "frozenQuoteDirectionHit", "intervalHit", "alwaysUpHit", "alwaysDownHit", "endpointPrice"):
            self.assertIsNone(row[key])
        self.assertEqual(evaluate(evidence=bars(), now=TARGET)["rows"][0]["evaluationStatus"], "UNSCORABLE")
        self.assertEqual(evaluate(evidence={"sh:600000": envelope(fetched=TARGET)}, now=TARGET)["rows"][0]["evaluationStatus"], "DESCRIPTIVE_ENDPOINT")

    def test_missing_unqualified_or_future_evidence(self):
        cases = [{}, {"600000": envelope()}]
        for key, value in (("timestampSemantics", "INFERRED_END_TIME"), ("priceBasis", "ADJUSTED"),
                           ("fetchedAt", "2020-01-02T10:32:00"), ("fetchedAt", (NOW + timedelta(seconds=1)).isoformat())):
            item = envelope()
            item[key] = value
            cases.append({"sh:600000": item})
        for change in ({"completionVerified": 1}, {"completionVerified": False}, {"endTime": "2020-01-02T10:30:00"}):
            item = envelope()
            item["bars"][0].update(change)
            cases.append({"sh:600000": item})
        cases.append({"sh:600000": envelope(fetched=TARGET - timedelta(seconds=1))})
        for evidence in cases:
            with self.subTest(evidence=evidence):
                row = evaluate(evidence=evidence)["rows"][0]
                self.assertEqual(row["evaluationStatus"], "UNSCORABLE")
                self.assertIsNone(row["frozenQuoteReturnPct"])

    def test_conflicting_equal_vintage_rejected_latest_revision_preserved(self):
        evidence = {"sh:600000": [envelope(price=10.2), envelope(price=10.3)]}
        self.assertEqual(evaluate(evidence=evidence)["rows"][0]["evaluationStatus"], "UNSCORABLE")
        evidence["sh:600000"][0]["fetchedAt"] = (NOW - timedelta(seconds=1)).isoformat()
        self.assertEqual(evaluate(evidence=evidence)["rows"][0]["endpointPrice"], 10.3)

    def test_stale_late_expired_and_invalid_timing_retained(self):
        cases = []
        stale = task()
        update_request(stale, quoteSourceAt=(ASOF - timedelta(seconds=120.001)).isoformat())
        cases.append((stale, "STALE_INPUT"))
        for at, expected in ((ASOF + timedelta(seconds=300.001), "LATE"), (TARGET, "EXPIRED"),
                             (TARGET + timedelta(seconds=1), "EXPIRED"), (ASOF - timedelta(seconds=1), "INVALID_TIMING"),
                             (NOW + timedelta(seconds=1), "INVALID_TIMING")):
            value = task()
            value["analysis"]["gmtCreate"] = at.isoformat()
            cases.append((value, expected))
        for value, expected in cases:
            with self.subTest(expected=expected):
                report = evaluate(value)
                self.assertEqual(report["summary"]["requestedTaskCount"], 1)
                self.assertEqual(report["rows"][0]["evaluationStatus"], expected)
                self.assertIsNone(report["rows"][0]["frozenQuoteDirectionHit"])

    def test_invalid_frozen_contract_and_ineligible_reason(self):
        for key, value in (("version", "OTHER"), ("maxQuoteAgeSeconds", 121), ("maxPublishDelaySeconds", 301),
                           ("maxQuoteAgeSeconds", True), ("asOfAt", "2020-01-02T10:00:00"),
                           ("targetAt", (TARGET + timedelta(seconds=1)).isoformat()),
                           ("quoteSourceAt", (ASOF + timedelta(seconds=1)).isoformat()), ("inputStatus", None),
                           ("priceBasis", None), ("priceBasis", "ADJUSTED"), ("inputStatus", [])):
            item = task()
            update_request(item, **{key: value})
            with self.subTest(key=key):
                self.assertEqual(evaluate(item)["rows"][0]["evaluationStatus"], "INVALID_REQUEST")
        item = task()
        update_request(item, inputStatus="OUTSIDE_CONTINUOUS_WINDOW")
        row = evaluate(item)["rows"][0]
        self.assertEqual(row["evaluationStatus"], "INELIGIBLE")
        self.assertEqual(row["inputStatus"], "OUTSIDE_CONTINUOUS_WINDOW")

    def test_helper_ineligible_null_values_and_millisecond_iso_z(self):
        for status in review.INPUT_STATUSES - {"ELIGIBLE"}:
            value = task()
            update_request(value, inputStatus=status, quoteSourceAt=None, referencePrice=None)
            row = evaluate(value)["rows"][0]
            self.assertEqual(row["requestStatus"], "VALID")
            self.assertEqual(row["inputStatus"], status)
            self.assertEqual(row["evaluationStatus"], "STALE_INPUT" if status == "STALE_INPUT" else "INELIGIBLE")
            self.assertIsNone(row["frozenQuoteReturnPct"])
        value = task()
        for key in ("asOfAt", "targetAt", "quoteSourceAt"):
            old = review.timestamp(request(value)[key]).astimezone(timezone.utc)
            update_request(value, **{key: old.replace(microsecond=123000).isoformat(timespec="milliseconds").replace("+00:00", "Z")})
        value["analysis"]["gmtCreate"] = "2020-01-02T02:05:00.123Z"
        evidence = {"sh:600000": envelope(TARGET.replace(microsecond=123000))}
        row = evaluate(value, evidence)["rows"][0]
        self.assertEqual(row["evaluationStatus"], "DESCRIPTIVE_ENDPOINT")
        self.assertEqual(row["quoteAgeSeconds"], 120)
        self.assertEqual(row["recordedDelaySeconds"], 300)
        self.assertEqual(row["asOfAt"], "2020-01-02T02:00:00.123000+00:00")
        future = task()
        update_request(future, inputStatus="FUTURE_INPUT", quoteSourceAt=(ASOF + timedelta(seconds=1)).isoformat())
        row = evaluate(future)["rows"][0]
        self.assertEqual(row["evaluationStatus"], "INELIGIBLE")
        self.assertEqual(row["inputStatus"], "FUTURE_INPUT")

    def test_nonfinite_bool_and_nonpositive_numbers_rejected_everywhere(self):
        for invalid in (float("nan"), float("inf"), float("-inf"), True, False, 0, -1, "10", 10 ** 1000):
            with self.subTest(value_type=type(invalid).__name__):
                item = task()
                update_request(item, referencePrice=invalid)
                self.assertEqual(evaluate(item)["rows"][0]["evaluationStatus"], "INVALID_REQUEST")
                for key in ("targetLow", "targetHigh"):
                    item = task()
                    item["rawResponse"]["intradayForecast"][key] = invalid
                    self.assertEqual(evaluate(item)["rows"][0]["forecastStatus"], "INVALID")
                self.assertEqual(evaluate(evidence=bars(price=invalid))["rows"][0]["evaluationStatus"], "UNSCORABLE")

    def test_original_raw_root_missing_invalid_and_abstain(self):
        for raw, expected in (({}, "MISSING"), (None, "MISSING"), ("not-json", "INVALID"),
                              ({"intradayForecast": None}, "INVALID"),
                              ({"intradayForecast": abstain()}, "ABSTAIN"),
                              ({"intradayForecast": {"status": "ABSTAIN"}}, "INVALID"),
                              ({"intradayForecast": {"status": "ABSTAIN", "direction": "UP"}}, "INVALID"),
                              ({"payload": task()["rawResponse"]}, "MISSING")):
            value = task()
            value["rawResponse"] = raw
            row = evaluate(value)["rows"][0]
            self.assertEqual(row["evaluationStatus"], expected)
            self.assertIsNone(row["frozenQuoteDirectionHit"])
        for direction, low, high in (("FLAT", 10, 11), ("UP", 11, 10)):
            value = task()
            value["rawResponse"]["intradayForecast"].update(direction=direction, targetLow=low, targetHigh=high)
            self.assertEqual(evaluate(value)["rows"][0]["evaluationStatus"], "INVALID")

    def test_backend_root_extraction_with_fences_and_surrounding_text(self):
        raw = json.dumps(task()["rawResponse"])
        for wrapped in (raw, "```json\n" + raw + "\n```", "Short introduction.\n" + raw + "\nShort ending.",
                        "Short introduction.\n```json\n" + raw + "\n```\nShort ending."):
            value = task()
            value["rawResponse"] = wrapped
            row = evaluate(value)["rows"][0]
            self.assertEqual(row["evaluationStatus"], "DESCRIPTIVE_ENDPOINT")
            self.assertTrue(row["frozenQuoteDirectionHit"])
        value = task()
        value["rawResponse"] = "```json\n" + json.dumps({"intradayForecast": abstain()}) + "\n```"
        self.assertEqual(evaluate(value)["rows"][0]["evaluationStatus"], "ABSTAIN")
        value["rawResponse"] = "Extra {} before actual JSON\n" + raw
        self.assertEqual(evaluate(value)["rows"][0]["evaluationStatus"], "INVALID")

    def test_request_digest_and_exact_projection_hash_are_required(self):
        for change in ("task_digest", "envelope_digest", "projection_digest", "projection_bytes", "object_projection", "missing_digest"):
            value = task()
            if change == "task_digest":
                value["requestDigest"] = "0" * 64
            elif change == "envelope_digest":
                value["requestEnvelope"]["requestDigest"] = "0" * 64
            elif change == "projection_digest":
                value["requestEnvelope"]["projectionDigest"] = "0" * 64
            elif change == "projection_bytes":
                value["requestEnvelope"]["projectionJson"] += " "
            elif change == "object_projection":
                value["requestEnvelope"]["projectionJson"] = json.loads(value["requestEnvelope"]["projectionJson"])
            else:
                value.pop("requestDigest")
            with self.subTest(change=change):
                report = evaluate(value)
                row = report["rows"][0]
                self.assertEqual(report["summary"]["requestedTaskCount"], 1)
                self.assertEqual(row["evaluationStatus"], "INVALID_REQUEST")
                self.assertEqual(row["requestIntegrityStatus"], "INVALID")
                self.assertEqual(row["reason"], "INVALID_REQUEST_ENVELOPE_INTEGRITY")
                self.assertIsNone(row["frozenQuoteReturnPct"])
        value = task()
        projection = json.loads(value["requestEnvelope"]["projectionJson"])
        projection["privateFixtureText"] = "\u539f\u6587"
        text = json.dumps(projection, indent=2, ensure_ascii=False)
        value["requestEnvelope"].update(projectionJson=text, projectionDigest=hashlib.sha256(text.encode("utf-8")).hexdigest())
        self.assertEqual(evaluate(value)["rows"][0]["requestIntegrityStatus"], "VERIFIED_DIGEST_BINDING")

    def test_nonfinite_derived_statistics_remain_reportable_without_nan(self):
        value = task()
        update_request(value, referencePrice=1e-308)
        value["rawResponse"]["intradayForecast"].update(targetLow=1, targetHigh=1e308)
        report = evaluate(value)
        self.assertEqual(report["rows"][0]["evaluationStatus"], "INVALID")
        self.assertIsNone(report["rows"][0]["intervalWidthPct"])
        json.dumps(report, allow_nan=False)
        value["rawResponse"]["intradayForecast"].update(targetLow=1, targetHigh=1)
        report = evaluate(value, bars(price=1e308))
        self.assertEqual(report["rows"][0]["evaluationStatus"], "UNSCORABLE")
        self.assertIsNone(report["rows"][0]["frozenQuoteReturnPct"])
        json.dumps(report, allow_nan=False)

    def test_zero_return_never_hits_either_direction_or_baselines(self):
        for direction in ("UP", "DOWN"):
            value = task()
            value["rawResponse"]["intradayForecast"]["direction"] = direction
            row = evaluate(value, bars(price=10))["rows"][0]
            self.assertEqual(row["frozenQuoteReturnPct"], 0)
            self.assertFalse(row["frozenQuoteDirectionHit"])
            self.assertFalse(row["alwaysUpHit"])
            self.assertFalse(row["alwaysDownHit"])
            self.assertTrue(row["intervalHit"])

    def test_down_return_and_interval_inclusive(self):
        value = task()
        value["rawResponse"]["intradayForecast"].update(direction="DOWN", targetLow=9, targetHigh=10)
        row = evaluate(value, bars(price=9))["rows"][0]
        self.assertTrue(row["frozenQuoteDirectionHit"])
        self.assertTrue(row["alwaysDownHit"])
        self.assertTrue(row["intervalHit"])
        self.assertAlmostEqual(row["intervalWidthPct"], 10)

    def test_json_string_projection_snake_case_and_db_timezone_proxy(self):
        value = task()
        flat = {"id": value["id"], "symbol": "600000", "market": "sh", "status": "SUCCESS",
                "request_digest": value["requestDigest"], "request_envelope_json": json.dumps(value["requestEnvelope"]),
                "raw_response": json.dumps(value["rawResponse"]), "analysis_id": 101, "analysis_task_id": 1,
                "analysis_status": "SUCCESS", "analysis_gmt_create": "2020-01-02 10:05:00"}
        row = evaluate(flat)["rows"][0]
        self.assertEqual(row["evaluationStatus"], "DESCRIPTIVE_ENDPOINT")
        self.assertEqual(row["recordedAt"], "2020-01-02T10:05:00+08:00")
        self.assertEqual(row["timingProvenance"], "DB_INSERT_PROXY")


class DenominatorTests(unittest.TestCase):
    def test_full_denominator_failures_missing_analysis_abstain_and_legacy(self):
        tasks = [task(index) for index in range(1, 9)]
        tasks[1]["status"] = "FAILED"
        tasks[1]["rawResponse"] = None
        tasks[1].pop("analysis")
        tasks[2].pop("analysis")
        tasks[3]["rawResponse"] = {"intradayForecast": abstain()}
        tasks[4]["rawResponse"] = {}
        tasks[5]["analysis"]["gmtCreate"] = (ASOF + timedelta(seconds=301)).isoformat()
        update_request(tasks[6], quoteSourceAt=(ASOF - timedelta(seconds=121)).isoformat())
        set_projection(tasks[7], {})
        report = review.evaluate(tasks, bars(), NOW)
        self.assertEqual(report["summary"]["requestedTaskCount"], 7)
        self.assertEqual(report["excludedNoM30RequestCount"], 1)
        self.assertEqual(report["summary"]["statusCounts"], {
            "DESCRIPTIVE_ENDPOINT": 1, "FAILED": 1, "NO_ANALYSIS": 1, "ABSTAIN": 1, "MISSING": 1, "LATE": 1, "STALE_INPUT": 1})
        self.assertEqual(sum(report["summary"]["statusCounts"].values()), 7)
        self.assertEqual(report["slotCoverage"]["status"], "UNKNOWN")
        self.assertIsNone(report["slotCoverage"]["coverageRate"])
        self.assertEqual(report["summary"]["pairedBaselines"]["taskIds"], ["1"])

    def test_same_cohort_baselines_and_last30_requires_known_by_asof(self):
        history = envelope(ASOF - timedelta(minutes=30), 9, ASOF)
        evidence = {"sh:600000": [envelope(), history]}
        second = task(2)
        second["symbol"] = "600001"
        evidence["sh:600001"] = [envelope(price=9.5), envelope(ASOF - timedelta(minutes=30), 11, NOW)]
        report = review.evaluate([task(), second], evidence, NOW)
        paired = report["summary"]["pairedBaselines"]
        self.assertNotIn("last30Trend", paired)
        self.assertEqual(paired["taskIds"], ["1", "2"])
        self.assertEqual(paired["alwaysUp"]["frozenQuoteDirectionHitRate"], 0.5)
        self.assertEqual(paired["alwaysDown"]["frozenQuoteDirectionHitRate"], 0.5)
        trend = report["summary"]["pairedLast30Trend"]
        self.assertEqual(trend["taskIds"], ["1"])
        self.assertEqual(trend["rawM30Direction"]["hits"], 1)
        self.assertEqual(trend["last30Trend"]["hits"], 1)
        self.assertIsNone(report["rows"][1]["last30Hit"])
        self.assertEqual(report["summary"]["last30MissingOrFlatCount"], 1)

    def test_trend_boundary_flat_and_missing_history_never_remove_score(self):
        for gap, expected in ((0, "UP"), (59.999, "UP"), (60, None), (-1, None)):
            history = envelope(ASOF - timedelta(minutes=30, seconds=gap), 9, ASOF)
            row = evaluate(evidence={"sh:600000": [envelope(), history]})["rows"][0]
            self.assertEqual(row["evaluationStatus"], "DESCRIPTIVE_ENDPOINT")
            self.assertEqual(row["last30Direction"], expected)
        history = envelope(ASOF - timedelta(minutes=30), 10, ASOF)
        row = evaluate(evidence={"sh:600000": [envelope(), history]})["rows"][0]
        self.assertEqual(row["last30Direction"], "FLAT")
        self.assertIsNone(row["last30Hit"])

    def test_missing_timing_bad_link_pending_and_unknown_envelopes(self):
        for change, expected in (({"gmtCreate": None}, "UNKNOWN_TIMING"), ({"analysisTaskId": 2}, "INVALID_ANALYSIS_LINK"),
                                 ({"status": "ERROR"}, "FAILED")):
            value = task()
            value["analysis"].update(change)
            self.assertEqual(evaluate(value)["rows"][0]["evaluationStatus"], expected)
        value = task()
        value["status"] = "SUBMITTED"
        self.assertEqual(evaluate(value)["rows"][0]["evaluationStatus"], "PENDING")
        report = review.evaluate([{"requestEnvelope": "broken"}], {}, NOW)
        self.assertEqual(report["unreadableEnvelopeTaskCount"], 1)
        self.assertIsNone(report["summary"]["pairedBaselines"]["rawM30Direction"]["frozenQuoteDirectionHitRate"])
        with self.assertRaises(ValueError):
            review.evaluate([task(), task()], bars(), NOW)


class ExportAndOutputTests(unittest.TestCase):
    def test_select_only_valid_date_allowlist_and_task_identity_join(self):
        sql = review.export_sql("2020-01-02", "ai_meeting")
        self.assertTrue(sql.startswith("SELECT "))
        for forbidden in ("INSERT", "UPDATE", "DELETE", "REPLACE", "CREATE", "DROP", "ALTER"):
            self.assertIsNone(re.search(r"\b" + forbidden + r"\b", sql.upper()))
        self.assertNotIn(";", sql)
        for required in ("FROM stock_watch_analysis_task t", "LEFT JOIN stock_watch_technical_analysis",
                         "linked.analysis_task_id = t.id", "linked.status = 'SUCCESS'", "linked.symbol = t.symbol",
                         "ORDER BY linked.gmt_create ASC, linked.id ASC LIMIT 1", "t.raw_response", "t.request_envelope_json", "t.request_digest"):
            self.assertIn(required, sql)
        self.assertNotIn("t.status =", sql)
        for day in ("2020-1-02", "2020-02-30", "2020-01-02' OR 1=1", "secret"):
            with self.assertRaises(ValueError):
                review.export_sql(day, "ai_meeting")
        for database in ("other_db", "ai_meeting;DROP", "`ai_meeting`"):
            with self.assertRaises(ValueError):
                review.export_sql("2020-01-02", database)

    def test_mocked_mysql_defaults_first_stdin_select_and_suppressed_errors(self):
        result = type("Result", (), {"returncode": 0, "stdout": json.dumps(task()) + "\n", "stderr": "PRIVATE"})()
        with patch.object(review.subprocess, "run", return_value=result) as run, patch.dict(review.os.environ, {"MYSQL_PWD": "PRIVATE"}):
            exported = review.mysql_tasks("2020-01-02", "ai_meeting", "/tmp/protected.cnf")
        self.assertEqual(exported[0]["id"], 1)
        command = run.call_args.args[0]
        self.assertTrue(command[1].startswith("--defaults-file="))
        self.assertFalse(any("password" in arg or "PRIVATE" in arg for arg in command))
        self.assertNotIn("MYSQL_PWD", run.call_args.kwargs["env"])
        self.assertTrue(run.call_args.kwargs["input"].startswith("SELECT"))
        result.returncode = 1
        with patch.object(review.subprocess, "run", return_value=result):
            with self.assertRaisesRegex(ValueError, "^mysql export failed$"):
                review.mysql_tasks("2020-01-02", "ai_meeting", None)

    def test_exclusive_output_json_csv_denominator_and_private_data_not_exported(self):
        value = task()
        value["requestEnvelope"]["credential"] = "PRIVATE_SENTINEL"
        value["rawResponse"]["privateEnvelope"] = "PRIVATE_SENTINEL"
        second = task(2)
        second["status"] = "FAILED"
        report = review.evaluate([value, second], bars(), NOW)
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "new-research"
            review.write_snapshot(output, report)
            original = (output / "report.json").read_bytes()
            self.assertNotIn(b"PRIVATE_SENTINEL", original)
            self.assertNotIn(value["requestDigest"].encode(), original)
            self.assertNotIn(value["requestEnvelope"]["projectionDigest"].encode(), original)
            saved = json.loads(original)
            self.assertEqual(saved["summary"]["requestedTaskCount"], 2)
            with (output / "tasks.csv").open(newline="") as stream:
                rows = list(csv.DictReader(stream))
            self.assertEqual(len(rows), 2)
            self.assertEqual(rows[1]["evaluationStatus"], "FAILED")
            self.assertEqual(rows[0]["classification"], "DESCRIPTIVE_ONLY")
            self.assertEqual(rows[0]["strictAsOfDirectionAccuracy"], "")
            self.assertEqual(rows[0]["strictAsOfDirectionAccuracyStatus"], "NOT_ESTABLISHED")
            self.assertFalse((output / "report.json").stat().st_mode & 0o222)
            with self.assertRaises(FileExistsError):
                review.write_snapshot(output, report)
            self.assertEqual((output / "report.json").read_bytes(), original)

    def test_cli_fixture_and_repeat_output_fails_before_any_export(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            fixture, barfile, output = root / "fixture.json", root / "bars.json", root / "research"
            fixture.write_text(json.dumps({"tasks": [task()]}), encoding="utf-8")
            barfile.write_text(json.dumps(bars()), encoding="utf-8")
            stdout, stderr = io.StringIO(), io.StringIO()
            with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
                self.assertEqual(review.main(["--input", str(fixture), "--bars-json", str(barfile), "--output", str(output)]), 0)
                original = (output / "report.json").read_bytes()
                with patch.object(review, "mysql_tasks", side_effect=AssertionError("must not export")):
                    self.assertEqual(review.main(["--date", "2020-01-02", "--output", str(output)]), 2)
            self.assertEqual((output / "report.json").read_bytes(), original)
            self.assertNotIn("rawResponse", stdout.getvalue())
            self.assertNotIn("requestEnvelope", stdout.getvalue())
            self.assertIn("requested=1 endpoints=1", stdout.getvalue())

    def test_cli_arguments_and_input_errors_do_not_echo_private_values(self):
        for arguments in (["--password=PRIVATE_SENTINEL"], ["--date", "PRIVATE_SENTINEL", "--output", "/does/not/exist/output"]):
            stdout, stderr = io.StringIO(), io.StringIO()
            with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
                try:
                    code = review.main(arguments)
                except SystemExit as error:
                    code = error.code
            self.assertEqual(code, 2)
            self.assertNotIn("PRIVATE_SENTINEL", stdout.getvalue() + stderr.getvalue())

    def test_existing_file_directory_and_symlink_outputs_are_never_reused(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            existing = root / "existing"
            existing.write_text("unchanged", encoding="utf-8")
            link = root / "link"
            link.symlink_to(existing)
            dangling = root / "dangling"
            dangling.symlink_to(root / "absent")
            for output in (existing, root, link, dangling):
                with contextlib.redirect_stderr(io.StringIO()), patch.object(review, "mysql_tasks", side_effect=AssertionError("must not export")):
                    self.assertEqual(review.main(["--date", "2020-01-02", "--output", str(output)]), 2)
            self.assertEqual(existing.read_text(), "unchanged")

    def test_zero_new_request_mysql_export_writes_normal_empty_snapshot(self):
        for source_tasks in ([], [{"id": 1, "requestEnvelope": {"projectionJson": {}}}]):
            result = type("Result", (), {"returncode": 0,
                         "stdout": "\n".join(json.dumps(value) for value in source_tasks), "stderr": ""})()
            with tempfile.TemporaryDirectory() as directory:
                output = Path(directory) / "empty-research"
                stdout = io.StringIO()
                with patch.object(review.subprocess, "run", return_value=result), contextlib.redirect_stdout(stdout):
                    self.assertEqual(review.main(["--date", "2020-01-02", "--output", str(output)]), 0)
                report = json.loads((output / "report.json").read_text())
                self.assertEqual(report["rows"], [])
                self.assertEqual(report["sourceTaskCount"], len(source_tasks))
                self.assertEqual(report["summary"]["requestedTaskCount"], 0)
                self.assertEqual(report["summary"]["statusCounts"], {})
                self.assertIsNone(report["summary"]["frozenQuoteDirectionHitRate"])
                self.assertIsNone(report["summary"]["strictAsOfDirectionAccuracy"])
                self.assertEqual(report["summary"]["strictAsOfDirectionAccuracyStatus"], "NOT_ESTABLISHED")
                self.assertIsNone(report["summary"]["recordingTimelyRate"])
                self.assertIsNone(report["summary"]["pairedBaselines"]["rawM30Direction"]["frozenQuoteDirectionHitRate"])
                self.assertEqual(report["slotCoverage"]["status"], "UNKNOWN")
                with (output / "tasks.csv").open(newline="") as stream:
                    self.assertEqual(list(csv.DictReader(stream)), [])
                self.assertIn("requested=0 endpoints=0", stdout.getvalue())


if __name__ == "__main__":
    unittest.main()
