package com.aimeeting.room.service;

import com.alibaba.fastjson.JSONObject;

import java.math.BigDecimal;
import java.util.List;

/**
 * A topic domain plugs domain-specific inputs into the one roundtable v2 flow. The flow never branches
 * on the domain; it only asks the domain that applies to the issue card. Without a matching domain the
 * meeting runs as a general topic. Further replaceable points (number caliber rules, reference-class data,
 * outcome judgment, default lenses) are added here as their slices are built.
 */
public interface MeetingDomain {

    /** True when the issue card names this domain's subject, for example a valid A-share code. */
    boolean appliesTo(JSONObject issueCard);

    /** Tells the moderator what this domain supplies automatically, so evidence questions skip it. */
    String issueCardHint();

    /** A field this domain reads from the issue card, as it appears in the output template (e.g. "stock":null). */
    default String issueCardField() {
        return null;
    }

    /** Copies this domain's validated fields (for example the stock code) from the raw card; invalid ones are dropped. */
    default void normalizeIssueCard(JSONObject raw, JSONObject normalized) {
    }

    /**
     * Evidence source, reference class and outcome judgment. Facts carry tier, statement, value, unit, period
     * and source but no id; the flow numbers them, and a failed lookup is a tier "G" gap. The outcome question,
     * when the domain has a judgeable result, is answered with probabilities instead of a single option.
     */
    DomainEvidence evidence(JSONObject issueCard);

    /**
     * At or after expiry: what actually happened to the outcome question's quantity, or null when this domain
     * does not judge outcomes for the card.
     */
    default OutcomeObservation observeOutcome(JSONObject issueCard, JSONObject outcomeQuestion) {
        return null;
    }

    final class OutcomeObservation {
        public static final String DUE = "DUE";
        public static final String NOT_DUE = "NOT_DUE";
        public static final String UNAVAILABLE = "UNAVAILABLE";

        public final String status;
        public final int elapsedTradingDays;
        public final String baseDate;
        public final BigDecimal baseClose;
        public final String endDate;
        public final BigDecimal endClose;
        public final String source;
        public final String reason;

        private OutcomeObservation(String status, int elapsedTradingDays, String baseDate, BigDecimal baseClose,
                                   String endDate, BigDecimal endClose, String source, String reason) {
            this.status = status;
            this.elapsedTradingDays = elapsedTradingDays;
            this.baseDate = baseDate;
            this.baseClose = baseClose;
            this.endDate = endDate;
            this.endClose = endClose;
            this.source = source;
            this.reason = reason;
        }

        public static OutcomeObservation due(int elapsed, String baseDate, BigDecimal baseClose,
                                             String endDate, BigDecimal endClose, String source) {
            return new OutcomeObservation(DUE, elapsed, baseDate, baseClose, endDate, endClose, source, null);
        }

        public static OutcomeObservation notDue(int elapsed) {
            return new OutcomeObservation(NOT_DUE, elapsed, null, null, null, null, null, null);
        }

        public static OutcomeObservation unavailable(String reason) {
            return new OutcomeObservation(UNAVAILABLE, 0, null, null, null, null, null, reason);
        }
    }

    final class DomainEvidence {
        public final List<JSONObject> facts;
        /** Null when the topic has no judgeable outcome. */
        public final JSONObject outcomeQuestion;

        public DomainEvidence(List<JSONObject> facts, JSONObject outcomeQuestion) {
            this.facts = facts;
            this.outcomeQuestion = outcomeQuestion;
        }
    }
}
