package com.aimeeting.room.service;

/**
 * Thread-scoped attribution for model and evidence calls made on behalf of a meeting.
 * Callers open a scope around a step; clients read {@link #current()} when they record a call.
 * Calls made outside any meeting scope (for example stock-watch jobs) are not attributed.
 */
public final class MeetingCallContext {

    public static final String ISSUE_CARD = "issue_card";
    public static final String DIVERGENCE = "divergence";
    public static final String DIVERGENCE_REWRITE = "divergence_rewrite";
    public static final String CROSS_EXAM = "cross_exam";
    public static final String CROSS_EXAM_REWRITE = "cross_exam_rewrite";
    public static final String RED_TEAM = "red_team";
    public static final String RED_TEAM_REWRITE = "red_team_rewrite";
    public static final String REESTIMATE = "reestimate";
    public static final String REESTIMATE_REWRITE = "reestimate_rewrite";
    public static final String CLASSIFY = "classify";
    public static final String CLASSIFY_REWRITE = "classify_rewrite";
    public static final String EVIDENCE_PLAN = "evidence_plan";
    public static final String EVIDENCE_SEARCH = "evidence_search";
    public static final String SPEECH = "speech";
    public static final String SPEECH_REWRITE = "speech_rewrite";
    public static final String VOTE = "vote";
    public static final String CLUSTER = "cluster";
    public static final String ROUND_SUMMARY = "round_summary";
    public static final String SUMMARY = "summary";
    public static final String SUMMARY_REWRITE = "summary_rewrite";
    public static final String DECISION_CARD = "decision_card";
    public static final String DECISION_CARD_REWRITE = "decision_card_rewrite";
    public static final String RESEARCH_PLAN = "research_plan";
    public static final String RESEARCH_MATCH = "research_match";
    public static final String SOLO = "solo";
    public static final String SOLO_REWRITE = "solo_rewrite";
    public static final String OTHER = "other";

    private static final ThreadLocal<Frame> CURRENT = new ThreadLocal<>();

    private MeetingCallContext() {
    }

    public static Frame current() {
        return CURRENT.get();
    }

    public static Scope enter(Long meetingId, Integer round, Long memberId, String stage) {
        return push(new Frame(meetingId, round, memberId, stage));
    }

    /** Narrows the current meeting scope to one step, keeping meeting, round and member. */
    public static Scope stage(String stage) {
        Frame outer = CURRENT.get();
        if (outer == null) return () -> { };
        return push(new Frame(outer.meetingId, outer.round, outer.memberId, stage));
    }

    /** Narrows the current meeting scope to one member's step. */
    public static Scope member(Long memberId, String stage) {
        Frame outer = CURRENT.get();
        if (outer == null) return () -> { };
        return push(new Frame(outer.meetingId, outer.round, memberId, stage));
    }

    private static Scope push(Frame frame) {
        Frame previous = CURRENT.get();
        CURRENT.set(frame);
        return () -> {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        };
    }

    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }

    public static final class Frame {
        public final Long meetingId;
        public final Integer round;
        public final Long memberId;
        public final String stage;

        private Frame(Long meetingId, Integer round, Long memberId, String stage) {
            this.meetingId = meetingId;
            this.round = round;
            this.memberId = memberId;
            this.stage = stage == null ? OTHER : stage;
        }
    }
}
