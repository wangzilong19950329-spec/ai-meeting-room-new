package com.aimeeting.room.service;

import com.alibaba.fastjson.JSONArray;
import com.aimeeting.room.dao.StockWatchTechnicalAnalysisMapper;
import com.aimeeting.room.dao.StockWatchTechnicalSessionMapper;
import com.aimeeting.room.entity.StockWatchTechnicalAnalysis;
import com.aimeeting.room.entity.StockWatchTechnicalSession;
import com.aimeeting.room.service.StockWatchAnalysisTaskService.Reservation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Date;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class StockWatchAnalysisProjectionService {

    private final StockWatchTechnicalAnalysisMapper analysisMapper;
    private final StockWatchPredictionService predictionService;
    private final StockWatchFactPersistenceService factPersistenceService;
    private final StockWatchTechnicalSessionMapper sessionMapper;
    private final StockWatchAnalysisTaskService analysisTaskService;

    @Transactional(rollbackFor = Exception.class)
    public StockWatchTechnicalAnalysis project(Reservation reservation,
                                                Long originObservationId,
                                                Map<String, Object> validatedIntent,
                                                StockWatchTechnicalAnalysis record,
                                                JSONArray forecasts,
                                                LocalDate forecastDate,
                                                Double sourcePrice,
                                                String inputSnapshotJson,
                                                StockWatchTechnicalSession session,
                                                String bridgeTargetId,
                                                String conversationUrl) {
        if (record == null || session == null || reservation == null) {
            throw new IllegalArgumentException("analysis_projection_input_required");
        }
        if (originObservationId != null) {
            factPersistenceService.registerModelIntentTests(originObservationId,
                    reservation.getTask().getId(), record.getSymbol(),
                    record.getMarket(), validatedIntent);
        }
        if (analysisMapper.insert(record) != 1) {
            throw new IllegalStateException("analysis_projection_insert_failed");
        }
        predictionService.savePredictionsStrict(record.getId(), record.getSymbol(), record.getMarket(),
                record.getStockName(), forecastDate, sourcePrice, forecasts, inputSnapshotJson);

        int used = session.getContextChars() == null ? 0 : session.getContextChars();
        int delta = record.getContextCharsDelta() == null ? 0 : record.getContextCharsDelta();
        int sessionUpdated = sessionMapper.updateAfterRun(session.getId(), bridgeTargetId, conversationUrl,
                used + Math.max(0, delta), new Date(), "SUCCESS", null);
        if (sessionUpdated != 1) {
            throw new IllegalStateException("analysis_projection_session_update_failed");
        }
        analysisTaskService.markProjectedSuccess(reservation);
        return record;
    }

    @Transactional(rollbackFor = Exception.class)
    public StockWatchTechnicalAnalysis projectFailure(Reservation reservation,
                                                       StockWatchTechnicalAnalysis record,
                                                       StockWatchTechnicalSession session,
                                                       String taskFailureStatus,
                                                       String rawResponse,
                                                       String contentStatus,
                                                       String completionReason,
                                                       String error,
                                                       String bridgeTargetId,
                                                       String conversationUrl) {
        if (reservation == null || !reservation.isNewSubmission() || record == null || session == null) {
            throw new IllegalStateException("analysis_failure_projection_requires_new_submission");
        }
        if ("INVALID_RESPONSE".equals(taskFailureStatus)) {
            analysisTaskService.markInvalid(reservation, rawResponse, contentStatus, completionReason, error);
        } else if ("UNKNOWN".equals(taskFailureStatus)) {
            analysisTaskService.markUnknown(reservation, rawResponse, error);
        } else {
            analysisTaskService.markFailed(reservation, rawResponse, error);
        }
        if (analysisMapper.insert(record) != 1) {
            throw new IllegalStateException("analysis_failure_projection_insert_failed");
        }
        return record;
    }

    @Transactional(rollbackFor = Exception.class)
    public StockWatchTechnicalAnalysis projectReplayFailure(Reservation reservation,
                                                             StockWatchTechnicalAnalysis record,
                                                             String error) {
        if (reservation == null || reservation.isNewSubmission() || record == null) {
            throw new IllegalStateException("analysis_replay_failure_projection_requires_existing_submission");
        }
        analysisTaskService.markProjectionInvalid(reservation, error);
        if (analysisMapper.insert(record) != 1) {
            throw new IllegalStateException("analysis_replay_failure_projection_insert_failed");
        }
        return record;
    }

    @Transactional(rollbackFor = Exception.class)
    public StockWatchTechnicalAnalysis projectAbandoned(Reservation reservation,
                                                         StockWatchTechnicalAnalysis record,
                                                         String terminalStatus,
                                                         String error) {
        if (reservation == null || reservation.isNewSubmission() || record == null) {
            throw new IllegalStateException("analysis_abandoned_projection_requires_existing_submission");
        }
        analysisTaskService.markAbandoned(reservation, terminalStatus, error);
        if (analysisMapper.insert(record) != 1) {
            throw new IllegalStateException("analysis_abandoned_projection_insert_failed");
        }
        return record;
    }

    public void updateFailureTelemetryBestEffort(StockWatchTechnicalSession session,
                                                 StockWatchTechnicalAnalysis record,
                                                 String bridgeTargetId,
                                                 String conversationUrl,
                                                 String error) {
        if (session == null || session.getId() == null || record == null) return;
        int used = session.getContextChars() == null ? 0 : session.getContextChars();
        int delta = record.getContextCharsDelta() == null ? 0 : record.getContextCharsDelta();
        try {
            int sessionUpdated = sessionMapper.updateAfterRun(session.getId(), bridgeTargetId, conversationUrl,
                    used + Math.max(0, delta), new Date(), "ERROR", error);
            if (sessionUpdated != 1) {
                log.warn("stock-watch failure telemetry could not update session analysisId={} sessionId={}",
                        record.getId(), session.getId());
            }
        } catch (Exception telemetryError) {
            log.warn("stock-watch failure telemetry update failed analysisId={} sessionId={} error={}",
                    record.getId(), session.getId(), telemetryError.getMessage());
        }
    }
}
