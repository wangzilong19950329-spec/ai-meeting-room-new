package com.aimeeting.room.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A leading plus sign marks a rise; only a minus sign makes a number negative. */
class ReviewedNumberGateSignTest {

    private static final String FACTS = "2026年9月比亚迪新能源汽车销量同比增长16.98%；近20日涨跌幅：-18.06%";

    @Test
    void aPlusSignKeepsTheNumberPositive() {
        ReviewedNumberGate.Audit audit = ReviewedNumberGate.audit("9月销量同比+16.98%。", FACTS, "", null);
        assertFalse(audit.hasUnsupported(), audit.toJson(0).toJSONString());
    }

    @Test
    void aMinusSignStillMakesItNegative() {
        assertFalse(ReviewedNumberGate.audit("近20日-18.06%。", FACTS, "", null).hasUnsupported());
        assertTrue(ReviewedNumberGate.audit("9月销量同比-16.98%。", FACTS, "", null).hasUnsupported());
    }
}
