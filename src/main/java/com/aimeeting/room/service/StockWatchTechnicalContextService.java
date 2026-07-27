package com.aimeeting.room.service;

import com.aimeeting.room.dao.StockWatchTechnicalAnalysisMapper;
import com.aimeeting.room.entity.StockWatchTechnicalAnalysis;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.sql.Date;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class StockWatchTechnicalContextService {

    private static final ZoneId CHINA_ZONE = ZoneId.of("Asia/Shanghai");
    private static final long MAX_INLINE_AGE_SECONDS = 4L * 60L * 60L;

    private final StockWatchTechnicalAnalysisMapper analysisMapper;
    private final StockWatchTechnicalEmailRenderer renderer;

    public StockWatchTechnicalAnalysis exactTrustedForSource(String symbol,
                                                             String market,
                                                             long quoteSourceEpochMs) {
        long quoteSeconds = quoteSourceEpochMs / 1000L;
        for (StockWatchTechnicalAnalysis record : trustedRecordsForQuoteDay(symbol, market, quoteSourceEpochMs)) {
            Long sourceSeconds = renderer.sourceTimestampEpochSeconds(record);
            if (Objects.equals(sourceSeconds, quoteSeconds)) return record;
        }
        return null;
    }

    public StockWatchTechnicalAnalysis latestTrustedHistorical(String symbol,
                                                               String market,
                                                               long quoteSourceEpochMs) {
        long quoteSeconds = quoteSourceEpochMs / 1000L;
        for (StockWatchTechnicalAnalysis record : trustedRecordsForQuoteDay(symbol, market, quoteSourceEpochMs)) {
            Long sourceSeconds = renderer.sourceTimestampEpochSeconds(record);
            if (sourceSeconds == null || sourceSeconds > quoteSeconds) continue;
            if (quoteSeconds - sourceSeconds <= MAX_INLINE_AGE_SECONDS) return record;
        }
        return null;
    }

    private List<StockWatchTechnicalAnalysis> trustedRecordsForQuoteDay(String symbol,
                                                                        String market,
                                                                        long quoteSourceEpochMs) {
        LocalDate date = Instant.ofEpochMilli(quoteSourceEpochMs).atZone(CHINA_ZONE).toLocalDate();
        Date day = Date.valueOf(date);
        return analysisMapper.selectRecentSuccessfulSlotsByDateRange(
                        symbol, market, day, day, "DAY", 20).stream()
                .filter(TrustedTechnicalRendererV1::isTrustedV6)
                .collect(Collectors.toList());
    }
}
