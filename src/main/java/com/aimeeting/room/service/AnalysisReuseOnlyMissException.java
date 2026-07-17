package com.aimeeting.room.service;

import lombok.Getter;

@Getter
public class AnalysisReuseOnlyMissException extends RuntimeException {

    private final String observationKey;

    public AnalysisReuseOnlyMissException(String observationKey) {
        super("analysis_reuse_only_miss:" + observationKey);
        this.observationKey = observationKey;
    }
}
