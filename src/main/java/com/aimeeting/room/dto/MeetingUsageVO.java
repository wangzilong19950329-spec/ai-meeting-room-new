package com.aimeeting.room.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** Model and evidence-call usage for one meeting: a total line plus one line per stage. */
@Data
public class MeetingUsageVO {
    private UsageLine total = new UsageLine();
    private List<UsageLine> stages = new ArrayList<>();

    @Data
    public static class UsageLine {
        /** Null on the total line. */
        private String stage;
        private int calls;
        /** Calls with provider-reported token counts (model API). */
        private int meteredCalls;
        /** Calls without token counts (web bridge, GEN-10); only characters are known. */
        private int unmeteredCalls;
        private long promptTokens;
        private long completionTokens;
        private long cacheHitTokens;
        private long promptChars;
        private long completionChars;
        private long durationMs;
    }
}
