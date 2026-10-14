package com.aimeeting.room.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StockWatchBridgeRequestIdentityTest {

    @Test
    void operationDigestBindsCapabilitySessionAndResponseMode() {
        String baseline = StockWatchBridgeRequestIdentity.digest(
                "分析当前行情", "stock.technical-analysis", "tenant/A", "expert",
                true, false, false, false, "");

        assertNotEquals(baseline, StockWatchBridgeRequestIdentity.digest(
                "分析当前行情", "stock.rule-evaluation", "tenant/A", "expert",
                true, false, false, false, ""));
        assertNotEquals(baseline, StockWatchBridgeRequestIdentity.digest(
                "分析当前行情", "stock.technical-analysis", "tenant_A", "expert",
                true, false, false, false, ""));
        assertNotEquals(baseline, StockWatchBridgeRequestIdentity.digest(
                "分析当前行情", "stock.technical-analysis", "tenant/A", "expert",
                false, false, false, false, ""));
        assertTrue(StockWatchBridgeRequestIdentity.canonicalPayload(
                "分析当前行情", "stock.technical-analysis", "tenant/A", "expert",
                true, false, false, false, "").startsWith("deepseek-operation-v1\n"));
        assertEquals("5b182803d73751e8a87e74e0ea389aeecabc0acb549f6dbd20c4c56adccde71f",
                baseline);
        assertEquals(baseline, StockWatchBridgeRequestIdentity.digest(
                "  分析当前行情\n", "stock.technical-analysis", "tenant/A", "expert",
                true, false, false, false, ""));
        assertEquals(baseline, StockWatchBridgeRequestIdentity.digest(
                "\u00A0\uFEFF分析当前行情\u3000\u2029", "stock.technical-analysis", "tenant/A", "expert",
                true, false, false, false, ""));
        assertEquals(baseline, StockWatchBridgeRequestIdentity.digest(
                "\u00A0分析当前行情\u3000", "\uFEFFstock.technical-analysis\u2029",
                "\u00A0tenant/A\u3000", "\u2028expert\uFEFF",
                true, false, false, false, "\u00A0\u3000"));
        assertEquals("b87ce3296f03064e7a7e192d29bf099020ff709fc2e54e446774d4cfff0cddce",
                StockWatchBridgeRequestIdentity.digestWithoutSearch(
                        "分析当前行情", "stock.technical-analysis", "tenant/A", "expert",
                        true, false, false, false, ""));
    }
}
