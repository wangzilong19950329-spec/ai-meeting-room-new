package com.aimeeting.room.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Chinese writes a fall as "下跌18.06%" while the fact base stores -18.06%; both describe the same number. */
class ReviewedNumberGateDeclineTest {

    private static final String FACTS = "近20个交易日涨跌幅（截至 2026-10-16）：-18.06%；"
            + "距近60个交易日最高价回撤（截至 2026-10-16，程序计算）：-32.21%；碳酸锂累计涨跌幅约为-32.80%";

    @Test
    void aFallWrittenAsAPositiveNumberAfterADeclineWordMatchesTheNegativeFact() {
        ReviewedNumberGate.Audit audit = ReviewedNumberGate.audit(
                "近20日下跌18.06%，距高点回撤32.21%，碳酸锂跌幅为32.80%。", FACTS, "", null);
        assertFalse(audit.hasUnsupported(), audit.toJson(0).toJSONString());
    }

    @Test
    void aRiseClaimedForAFallingNumberIsStillUnsupported() {
        ReviewedNumberGate.Audit audit = ReviewedNumberGate.audit("近20日上涨18.06%。", FACTS, "", null);
        assertTrue(audit.hasUnsupported(), audit.toJson(0).toJSONString());
    }

    @Test
    void aLevelAfterDeclineToIsNotTreatedAsAFall() {
        ReviewedNumberGate.Audit audit = ReviewedNumberGate.audit("毛利率下降至18.06%。", FACTS, "", null);
        assertTrue(audit.hasUnsupported(), audit.toJson(0).toJSONString());
    }
}
