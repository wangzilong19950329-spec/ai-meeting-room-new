package com.aimeeting.room.dto;

import lombok.Data;

import java.util.Date;

/** A tracking item the program asks for when a meeting concludes, instead of one per open question. */
@Data
public class ProgramTrackingItemSpec {
    /** OUTCOME_EXPIRY (judged by the program at expiry) or ASSUMPTION_DISPUTE (judged by a person). */
    private String itemType;
    private String title;
    private String description;
    /** SYSTEM or HUMAN. */
    private String ownerType;
    private boolean autoRefresh;
    /** First check; null when nothing is checked automatically. */
    private Date nextCheckAt;
    private Date dueAt;
}
