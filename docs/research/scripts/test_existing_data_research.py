import importlib.util
import pathlib
import unittest

MODULE_PATH = pathlib.Path(__file__).with_name("analyze-stock-watch-existing-data-2026-10-04.py")
SPEC = importlib.util.spec_from_file_location("research", MODULE_PATH)
research = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(research)


class ResearchTest(unittest.TestCase):
    def test_legacy_direction_boundaries_are_inclusive(self):
        self.assertEqual(research.classified(1), "UP")
        self.assertEqual(research.classified(-1), "DOWN")
        self.assertEqual(research.classified(0), "SIDEWAYS")

    def setUp(self):
        self.row = {"symbol": "588200", "market": "sh", "forecast_date": "2026-07-20",
                    "source_price": 300, "horizon_trading_days": 1, "direction": "UP",
                    "status": "PENDING", "probability": 0.6, "target_low": 320, "target_high": 340}
        self.analysis = {"snapshot": {"quote": {"timestamp": 1784514600, "previousClose": 300}}}
        self.catalog = {("588200", "sh"): {"analysis_id": 1, "bars": [
            {"date": "2026-07-17", "close": 100},
            {"date": "2026-07-21", "close": 110, "high": 115, "low": 90}]}}

    def test_price_scale_is_aligned_and_no_stored_invalidation_is_reused(self):
        result = research.reprice(self.row, self.analysis, self.catalog)
        self.assertAlmostEqual(result["ret"], 10)
        self.assertAlmostEqual(result["low_pct"], -10)
        self.assertEqual(result["scale"], 3)
        self.assertTrue(result["direction_hit"])
        self.assertIsNone(result["invalidated"])

    def test_missing_prior_reference_is_not_assumed_unit_scale(self):
        self.catalog[("588200", "sh")]["bars"].pop(0)
        self.assertIsNone(research.reprice(self.row, self.analysis, self.catalog))

    def test_missing_horizon_is_not_scored(self):
        self.row["horizon_trading_days"] = 5
        self.assertIsNone(research.reprice(self.row, self.analysis, self.catalog))

    def test_quote_date_must_equal_forecast_date(self):
        self.row["forecast_date"] = "2026-07-17"
        self.assertIsNone(research.reprice(self.row, self.analysis, self.catalog))

    def test_earliest_forecast_is_selected_before_maturity_filter(self):
        base = dict(self.row, horizon_code="T1", id=1, gmt_create="2026-07-20 10:00:00")
        later = dict(base, id=2, status="EVALUATED", gmt_create="2026-07-20 11:00:00")
        self.assertEqual(research.canonical([later, base]), [base])

    def test_intraday_current_day_bar_is_not_completed(self):
        analysis = {"id": 1, "symbol": "588200", "market": "sh", "gmt_create": "2026-07-20 10:30:00",
                    "snapshot": {"quote": {"timestamp": 1784514600}, "dailyKline": [
                        {"date": "2026-07-17", "close": 100}, {"date": "2026-07-20", "close": 110}]}}
        bars = research.completed_bar_catalog({1: analysis})[("588200", "sh")]["bars"]
        self.assertEqual([bar["date"] for bar in bars], ["2026-07-17"])


if __name__ == "__main__":
    unittest.main()
