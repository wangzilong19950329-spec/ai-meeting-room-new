package com.aimeeting.room.service;

import com.alibaba.fastjson.JSONObject;

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
