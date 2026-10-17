package com.aimeeting.room.service;

import com.aimeeting.room.entity.AiMeetingTrackingItem;

import java.util.Date;

/** Refreshes a tracking item type with program logic instead of a web search. */
public interface ProgramTrackingHandler {

    boolean supports(String itemType);

    Check refresh(AiMeetingTrackingItem item);

    final class Check {
        /** success (judged), pending (not due yet) or error. */
        public final String status;
        public final String note;
        /** Null stops automatic checks. */
        public final Date nextCheckAt;
        /** True once the item has its final answer and waits for a person to review it. */
        public final boolean done;

        public Check(String status, String note, Date nextCheckAt, boolean done) {
            this.status = status;
            this.note = note;
            this.nextCheckAt = nextCheckAt;
            this.done = done;
        }
    }
}
