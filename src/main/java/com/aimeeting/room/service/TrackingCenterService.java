package com.aimeeting.room.service;

import com.aimeeting.room.dao.AiMeetingRoomMapper;
import com.aimeeting.room.dao.AiMeetingTrackingItemMapper;
import com.aimeeting.room.dao.AiMeetingTrackingUpdateMapper;
import com.aimeeting.room.dto.TrackingItemCreateRequest;
import com.aimeeting.room.dto.TrackingOverviewVO;
import com.aimeeting.room.entity.AiMeetingRoom;
import com.aimeeting.room.entity.AiMeetingTrackingItem;
import com.aimeeting.room.entity.AiMeetingTrackingUpdate;
import com.aimeeting.room.enums.MeetingStatusEnum;
import com.aimeeting.room.enums.TrackingItemStatusEnum;
import com.aimeeting.room.service.ai.WebSearchService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
public class TrackingCenterService {

    private static final ZoneId DEFAULT_ZONE = ZoneId.systemDefault();

    private final AiMeetingTrackingItemMapper trackingItemMapper;
    private final AiMeetingTrackingUpdateMapper trackingUpdateMapper;
    private final AiMeetingRoomMapper roomMapper;
    private final WebSearchService webSearchService;
    private final MeetingRoomService meetingRoomService;
    private final ExecutorService trackingRefreshExecutor;
    private final AtomicBoolean refreshDispatching = new AtomicBoolean(false);

    @Value("${ai.tracking.refresh.enabled:true}")
    private boolean refreshEnabled;

    @Value("${ai.tracking.refresh.batch-size:2}")
    private int refreshBatchSize;

    public TrackingCenterService(AiMeetingTrackingItemMapper trackingItemMapper,
                                 AiMeetingTrackingUpdateMapper trackingUpdateMapper,
                                 AiMeetingRoomMapper roomMapper,
                                 WebSearchService webSearchService,
                                 MeetingRoomService meetingRoomService,
                                 @Qualifier("trackingRefreshExecutor") ExecutorService trackingRefreshExecutor) {
        this.trackingItemMapper = trackingItemMapper;
        this.trackingUpdateMapper = trackingUpdateMapper;
        this.roomMapper = roomMapper;
        this.webSearchService = webSearchService;
        this.meetingRoomService = meetingRoomService;
        this.trackingRefreshExecutor = trackingRefreshExecutor;
    }

    public TrackingOverviewVO overview() {
        List<AiMeetingTrackingItem> items = trackingItemMapper.selectAll();
        return buildOverview(items);
    }

    public TrackingOverviewVO.TrackingItemVO createCustomItem(TrackingItemCreateRequest request) {
        AiMeetingTrackingItem item = new AiMeetingTrackingItem();
        Date now = new Date();
        item.setCreatorId(request.getCreatorId());
        item.setMeetingId(request.getMeetingId());
        item.setWorkflowRunId(null);
        item.setStageRunId(null);
        item.setSourceType("CUSTOM");
        item.setItemType(firstNonBlank(request.getItemType(), "CUSTOM_WATCH"));
        item.setTitle(request.getTitle().trim());
        item.setDescription(trimToNull(request.getDescription()));
        item.setStatus(TrackingItemStatusEnum.OPEN.getCode());
        item.setOwnerType(firstNonBlank(trimToNull(request.getOwnerType()), "HUMAN"));
        item.setOwnerRef(trimToNull(request.getOwnerRef()));
        item.setReopenTrigger(Boolean.TRUE.equals(request.getReopenTrigger()) ? 1 : 0);
        item.setRequiredForContinue(Boolean.TRUE.equals(request.getRequiredForContinue()) ? 1 : 0);
        item.setReopenPolicy(resolveReopenPolicy(request.getReopenPolicy(), request.getMeetingId() != null));
        item.setAutoRefreshEnabled(resolveAutoRefreshEnabled(request.getAutoRefreshEnabled(), request.getRefreshMode()));
        item.setRefreshMode(resolveRefreshMode(request.getRefreshMode()));
        item.setRefreshIntervalHours(resolveRefreshIntervalHours(request.getRefreshIntervalHours(), item.getRefreshMode()));
        item.setWatchQuery(firstNonBlank(trimToNull(request.getWatchQuery()), item.getTitle()));
        item.setWatchScope(trimToNull(request.getWatchScope()));
        item.setNextCheckAt(computeNextCheckAt(item, now));
        item.setLastCheckedAt(null);
        item.setLastCheckStatus(null);
        item.setLastCheckNote(null);
        item.setLastCheckFingerprint(null);
        item.setChangeSeverity("NONE");
        item.setDueAt(request.getDueAt());
        item.setLastUpdateAt(now);
        item.setClosedAt(null);
        trackingItemMapper.insert(item);

        AiMeetingTrackingUpdate update = new AiMeetingTrackingUpdate();
        update.setTrackingItemId(item.getId());
        update.setMeetingId(item.getMeetingId());
        update.setUpdateType("CREATED");
        update.setStatusBefore(null);
        update.setStatusAfter(TrackingItemStatusEnum.OPEN.getCode());
        update.setContent("已创建自定义追踪项，等待首次刷新。");
        update.setEvidenceRef(null);
        trackingUpdateMapper.insert(update);
        return buildTrackingItemVO(trackingItemMapper.selectById(item.getId()));
    }

    public TrackingOverviewVO.TrackingItemVO refreshItemNow(Long trackingItemId) {
        AiMeetingTrackingItem item = requireItem(trackingItemId);
        refreshItemInternal(item, "MANUAL_REFRESH");
        return buildTrackingItemVO(trackingItemMapper.selectById(trackingItemId));
    }

    public void reopenMeeting(Long trackingItemId) {
        AiMeetingTrackingItem item = requireItem(trackingItemId);
        if (item.getMeetingId() == null) {
            throw new IllegalStateException("该追踪项未绑定会议，不能重开会议");
        }
        String severity = firstNonBlank(item.getChangeSeverity(), "NONE").toUpperCase();
        if (!"MATERIAL".equals(severity)) {
            throw new IllegalStateException("当前追踪项尚未标记为关键变化，暂不建议重开");
        }
        meetingRoomService.reopenMeetingFromTrackingSignal(
                item.getMeetingId(),
                item.getId(),
                item.getTitle(),
                "追踪项检测到影响原结论的关键变化，建议重新审视结论。",
                item.getLastCheckNote(),
                latestEvidenceRef(item.getId())
        );
    }

    @Scheduled(
            fixedDelayString = "${ai.tracking.refresh.scan-interval-ms:60000}",
            initialDelayString = "${ai.tracking.refresh.initial-delay-ms:20000}"
    )
    public void dispatchDueRefreshes() {
        if (!refreshEnabled) {
            return;
        }
        if (!refreshDispatching.compareAndSet(false, true)) {
            return;
        }
        trackingRefreshExecutor.submit(() -> {
            try {
                processDueRefreshBatch();
            } catch (Exception e) {
                log.error("tracking refresh batch failed", e);
            } finally {
                refreshDispatching.set(false);
            }
        });
    }

    private void processDueRefreshBatch() {
        List<AiMeetingTrackingItem> dueItems = trackingItemMapper.selectDueForRefresh(new Date(), Math.max(1, refreshBatchSize));
        for (AiMeetingTrackingItem item : dueItems) {
            try {
                refreshItemInternal(item, "AUTO_REFRESH");
            } catch (Exception e) {
                log.warn("tracking refresh failed, itemId={}, title={}, err={}", item.getId(), item.getTitle(), e.getMessage());
                markRefreshFailure(item, e.getMessage());
            }
        }
    }

    private void refreshItemInternal(AiMeetingTrackingItem item, String updateType) {
        Date now = new Date();
        String query = firstNonBlank(trimToNull(item.getWatchQuery()), item.getTitle());
        webSearchService.beginSession();
        List<WebSearchService.SearchResult> results = webSearchService.search(query, 4);
        WebSearchService.SearchMeta meta = webSearchService.consumeSessionMeta();
        WebSearchService.ResearchDigest digest = webSearchService.buildResearchDigest(
                item.getTitle(),
                "tracking_followup",
                inferTargetYear(item.getTitle()),
                Collections.singletonList(query),
                results
        );

        String summary = buildRefreshSummary(item, digest, results);
        String fingerprint = buildFingerprint(summary, results);
        String severity = resolveChangeSeverity(item.getLastCheckFingerprint(), fingerprint, item.getLastCheckNote(), summary);
        String status = results.isEmpty() ? "no_result" : "success";
        String note = buildRefreshNote(query, summary, digest, meta, severity, results, updateType);
        String evidenceRef = buildEvidenceRef(results);

        trackingItemMapper.updateRefreshState(
                item.getId(),
                computeNextCheckAt(item, now),
                now,
                status,
                note,
                fingerprint,
                severity,
                now
        );

        AiMeetingTrackingUpdate update = new AiMeetingTrackingUpdate();
        update.setTrackingItemId(item.getId());
        update.setMeetingId(item.getMeetingId());
        update.setUpdateType(updateType);
        update.setStatusBefore(item.getStatus());
        update.setStatusAfter(item.getStatus());
        update.setContent(note);
        update.setEvidenceRef(evidenceRef);
        trackingUpdateMapper.insert(update);

        if ("MATERIAL".equals(severity)
                && item.getMeetingId() != null
                && "AUTO".equalsIgnoreCase(item.getReopenPolicy())) {
            meetingRoomService.reopenMeetingFromTrackingSignal(
                    item.getMeetingId(),
                    item.getId(),
                    item.getTitle(),
                    "自动刷新检测到关键变化，需要重新评估既有结论。",
                    summary,
                    evidenceRef
            );
        }
    }

    private void markRefreshFailure(AiMeetingTrackingItem item, String message) {
        Date now = new Date();
        String note = "自动刷新失败：" + firstNonBlank(trimToNull(message), "未知错误");
        trackingItemMapper.updateRefreshState(
                item.getId(),
                computeRetryCheckAt(item, now),
                now,
                "error",
                note,
                item.getLastCheckFingerprint(),
                firstNonBlank(item.getChangeSeverity(), "NONE"),
                now
        );
        AiMeetingTrackingUpdate update = new AiMeetingTrackingUpdate();
        update.setTrackingItemId(item.getId());
        update.setMeetingId(item.getMeetingId());
        update.setUpdateType("AUTO_REFRESH_ERROR");
        update.setStatusBefore(item.getStatus());
        update.setStatusAfter(item.getStatus());
        update.setContent(note);
        update.setEvidenceRef(null);
        trackingUpdateMapper.insert(update);
    }

    private TrackingOverviewVO buildOverview(List<AiMeetingTrackingItem> items) {
        TrackingOverviewVO overview = new TrackingOverviewVO();
        if (items == null || items.isEmpty()) {
            return overview;
        }
        List<TrackingOverviewVO.TrackingItemVO> itemVOs = new ArrayList<>();
        Date now = new Date();
        for (AiMeetingTrackingItem item : items) {
            TrackingOverviewVO.TrackingItemVO vo = buildTrackingItemVO(item);
            itemVOs.add(vo);
            overview.setTotalCount(overview.getTotalCount() + 1);
            if (Boolean.TRUE.equals(vo.getAutoRefreshEnabled())) {
                overview.setAutoRefreshCount(overview.getAutoRefreshCount() + 1);
            }
            if (vo.getNextCheckAt() != null && !vo.getNextCheckAt().after(new Date(now.getTime() + TimeUnit.HOURS.toMillis(1)))) {
                overview.setDueSoonCount(overview.getDueSoonCount() + 1);
            }
            if ("MATERIAL".equalsIgnoreCase(vo.getChangeSeverity())) {
                overview.setMaterialChangeCount(overview.getMaterialChangeCount() + 1);
            }
            if ("CUSTOM".equalsIgnoreCase(vo.getSourceType())) {
                overview.setCustomCount(overview.getCustomCount() + 1);
            }
            if (vo.getMeetingId() != null) {
                overview.setMeetingLinkedCount(overview.getMeetingLinkedCount() + 1);
            }
        }
        itemVOs.sort(Comparator
                .comparing((TrackingOverviewVO.TrackingItemVO vo) -> "MATERIAL".equalsIgnoreCase(vo.getChangeSeverity()) ? 0 : 1)
                .thenComparing(vo -> vo.getNextCheckAt() == null ? Long.MAX_VALUE : vo.getNextCheckAt().getTime())
                .thenComparing(TrackingOverviewVO.TrackingItemVO::getId, Comparator.nullsLast(Long::compareTo)));
        overview.setItems(itemVOs);
        return overview;
    }

    private TrackingOverviewVO.TrackingItemVO buildTrackingItemVO(AiMeetingTrackingItem item) {
        TrackingOverviewVO.TrackingItemVO vo = new TrackingOverviewVO.TrackingItemVO();
        if (item == null) {
            return vo;
        }
        vo.setId(item.getId());
        vo.setCreatorId(item.getCreatorId());
        vo.setMeetingId(item.getMeetingId());
        if (item.getMeetingId() != null) {
            AiMeetingRoom room = roomMapper.selectById(item.getMeetingId());
            if (room != null) {
                vo.setMeetingTitle(room.getTitle());
                vo.setMeetingStatus(room.getStatus());
                MeetingStatusEnum meetingStatusEnum = room.getStatus() != null ? MeetingStatusEnum.of(room.getStatus()) : null;
                vo.setMeetingStatusName(meetingStatusEnum != null ? meetingStatusEnum.getDesc() : null);
            }
        }
        vo.setSourceType(item.getSourceType());
        vo.setItemType(item.getItemType());
        vo.setTitle(item.getTitle());
        vo.setDescription(item.getDescription());
        vo.setStatus(item.getStatus());
        TrackingItemStatusEnum statusEnum = TrackingItemStatusEnum.of(item.getStatus());
        vo.setStatusName(statusEnum != null ? statusEnum.getDesc() : item.getStatus());
        vo.setOwnerType(item.getOwnerType());
        vo.setOwnerRef(item.getOwnerRef());
        vo.setReopenTrigger(item.getReopenTrigger() != null && item.getReopenTrigger() == 1);
        vo.setRequiredForContinue(item.getRequiredForContinue() != null && item.getRequiredForContinue() == 1);
        vo.setReopenPolicy(item.getReopenPolicy());
        vo.setAutoRefreshEnabled(item.getAutoRefreshEnabled() != null && item.getAutoRefreshEnabled() == 1);
        vo.setRefreshMode(item.getRefreshMode());
        vo.setRefreshIntervalHours(item.getRefreshIntervalHours());
        vo.setWatchQuery(item.getWatchQuery());
        vo.setWatchScope(item.getWatchScope());
        vo.setNextCheckAt(item.getNextCheckAt());
        vo.setLastCheckedAt(item.getLastCheckedAt());
        vo.setLastCheckStatus(item.getLastCheckStatus());
        vo.setLastCheckNote(item.getLastCheckNote());
        vo.setChangeSeverity(item.getChangeSeverity());
        vo.setDueAt(item.getDueAt());
        vo.setLastUpdateAt(item.getLastUpdateAt());
        vo.setClosedAt(item.getClosedAt());

        List<TrackingOverviewVO.TrackingUpdateVO> updates = buildUpdates(item.getId());
        vo.setUpdates(updates);
        if (!updates.isEmpty()) {
            vo.setLatestUpdateSummary(buildUpdateSummary(updates.get(0)));
        }
        List<TrackingOverviewVO.TrackingUpdateVO> todayUpdates = filterTodayUpdates(updates);
        vo.setTodayUpdates(todayUpdates);
        vo.setTodayUpdateCount(todayUpdates.size());
        return vo;
    }

    private List<TrackingOverviewVO.TrackingUpdateVO> buildUpdates(Long trackingItemId) {
        List<AiMeetingTrackingUpdate> updates = trackingUpdateMapper.selectByTrackingItemId(trackingItemId);
        List<TrackingOverviewVO.TrackingUpdateVO> result = new ArrayList<>();
        for (AiMeetingTrackingUpdate update : updates) {
            TrackingOverviewVO.TrackingUpdateVO vo = new TrackingOverviewVO.TrackingUpdateVO();
            vo.setId(update.getId());
            vo.setUpdateType(update.getUpdateType());
            vo.setStatusBefore(update.getStatusBefore());
            vo.setStatusAfter(update.getStatusAfter());
            vo.setContent(update.getContent());
            vo.setEvidenceRef(update.getEvidenceRef());
            vo.setGmtCreate(update.getGmtCreate());
            result.add(vo);
        }
        return result;
    }

    private List<TrackingOverviewVO.TrackingUpdateVO> filterTodayUpdates(List<TrackingOverviewVO.TrackingUpdateVO> updates) {
        List<TrackingOverviewVO.TrackingUpdateVO> today = new ArrayList<>();
        LocalDate current = LocalDate.now();
        for (TrackingOverviewVO.TrackingUpdateVO update : updates) {
            if (update == null || update.getGmtCreate() == null) {
                continue;
            }
            LocalDate date = Instant.ofEpochMilli(update.getGmtCreate().getTime()).atZone(DEFAULT_ZONE).toLocalDate();
            if (current.equals(date)) {
                today.add(update);
            }
        }
        return today;
    }

    private String buildUpdateSummary(TrackingOverviewVO.TrackingUpdateVO update) {
        if (update == null) {
            return null;
        }
        if (StringUtils.hasText(update.getContent())) {
            return update.getContent();
        }
        if (StringUtils.hasText(update.getStatusAfter())) {
            TrackingItemStatusEnum statusEnum = TrackingItemStatusEnum.of(update.getStatusAfter());
            return "最新状态：" + (statusEnum != null ? statusEnum.getDesc() : update.getStatusAfter());
        }
        return null;
    }

    private Date computeNextCheckAt(AiMeetingTrackingItem item, Date now) {
        if (item == null || item.getAutoRefreshEnabled() == null || item.getAutoRefreshEnabled() != 1) {
            return null;
        }
        String refreshMode = resolveRefreshMode(item.getRefreshMode());
        int intervalHours = resolveRefreshIntervalHours(item.getRefreshIntervalHours(), refreshMode);
        long baseMillis = TimeUnit.HOURS.toMillis(intervalHours);
        long jitterMillis = TimeUnit.MINUTES.toMillis(ThreadLocalRandom.current().nextInt(5, 46));
        return new Date(now.getTime() + baseMillis + jitterMillis);
    }

    private Date computeRetryCheckAt(AiMeetingTrackingItem item, Date now) {
        int hours = Math.min(24, Math.max(1, resolveRefreshIntervalHours(item.getRefreshIntervalHours(), item.getRefreshMode()) / 2));
        long jitterMillis = TimeUnit.MINUTES.toMillis(ThreadLocalRandom.current().nextInt(10, 41));
        return new Date(now.getTime() + TimeUnit.HOURS.toMillis(hours) + jitterMillis);
    }

    private String buildRefreshSummary(AiMeetingTrackingItem item,
                                       WebSearchService.ResearchDigest digest,
                                       List<WebSearchService.SearchResult> results) {
        if (digest != null && StringUtils.hasText(digest.summary)) {
            return digest.summary.trim();
        }
        if (results == null || results.isEmpty()) {
            return "本次未检索到可用于更新该追踪项的新结果。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("围绕“").append(item.getTitle()).append("”检索到 ").append(results.size()).append(" 条结果。");
        int limit = Math.min(results.size(), 2);
        for (int i = 0; i < limit; i++) {
            WebSearchService.SearchResult result = results.get(i);
            sb.append("来源").append(i + 1).append("：");
            if (StringUtils.hasText(result.source)) {
                sb.append(result.source.trim());
            }
            if (StringUtils.hasText(result.snippet)) {
                sb.append(" - ").append(truncate(result.snippet.trim(), 120));
            }
            sb.append("。");
        }
        return sb.toString();
    }

    private String buildRefreshNote(String query,
                                    String summary,
                                    WebSearchService.ResearchDigest digest,
                                    WebSearchService.SearchMeta meta,
                                    String severity,
                                    List<WebSearchService.SearchResult> results,
                                    String updateType) {
        StringBuilder sb = new StringBuilder();
        sb.append(updateType != null && updateType.startsWith("MANUAL") ? "手动刷新查询：" : "自动刷新查询：")
                .append(query).append("。");
        sb.append("变化等级：").append(severity).append("。");
        if (meta != null && StringUtils.hasText(meta.provider)) {
            sb.append("搜索源：").append(meta.provider).append("。");
        }
        if (digest != null && StringUtils.hasText(digest.verificationLevel)) {
            sb.append("交叉验证：").append(digest.verificationLevel).append("。");
        }
        if (digest != null && StringUtils.hasText(digest.freshnessNote)) {
            sb.append("时效判断：").append(digest.freshnessNote.trim()).append("。");
        }
        if (StringUtils.hasText(summary)) {
            sb.append("摘要：").append(summary.trim());
        } else if (results.isEmpty()) {
            sb.append("本次未检索到有效结果。");
        }
        return truncate(sb.toString(), 900);
    }

    private String buildEvidenceRef(List<WebSearchService.SearchResult> results) {
        if (results == null || results.isEmpty()) {
            return null;
        }
        List<String> refs = new ArrayList<>();
        for (WebSearchService.SearchResult result : results) {
            String ref = trimToNull(result.url);
            if (ref == null) {
                ref = trimToNull(result.source);
            }
            if (ref != null && !refs.contains(ref)) {
                refs.add(ref);
            }
            if (refs.size() >= 3) {
                break;
            }
        }
        return refs.isEmpty() ? null : String.join(" | ", refs);
    }

    private String latestEvidenceRef(Long trackingItemId) {
        List<AiMeetingTrackingUpdate> updates = trackingUpdateMapper.selectByTrackingItemId(trackingItemId);
        for (AiMeetingTrackingUpdate update : updates) {
            if (update != null && StringUtils.hasText(update.getEvidenceRef())) {
                return update.getEvidenceRef();
            }
        }
        return null;
    }

    private String resolveChangeSeverity(String previousFingerprint,
                                         String currentFingerprint,
                                         String previousSummary,
                                         String currentSummary) {
        if (!StringUtils.hasText(currentSummary)) {
            return firstNonBlank(previousFingerprint, "").isEmpty() ? "NONE" : "MINOR";
        }
        if (!StringUtils.hasText(previousFingerprint) || !StringUtils.hasText(previousSummary)) {
            return "MINOR";
        }
        if (previousFingerprint.equals(currentFingerprint)) {
            return "NONE";
        }
        double similarity = computeSimilarity(previousSummary, currentSummary);
        if (similarity >= 0.92d) {
            return "NONE";
        }
        if (similarity >= 0.68d) {
            return "MINOR";
        }
        return "MATERIAL";
    }

    private double computeSimilarity(String left, String right) {
        Set<String> a = toShingles(left);
        Set<String> b = toShingles(right);
        if (a.isEmpty() || b.isEmpty()) {
            return 0d;
        }
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        Set<String> intersection = new HashSet<>(a);
        intersection.retainAll(b);
        return union.isEmpty() ? 0d : (double) intersection.size() / (double) union.size();
    }

    private Set<String> toShingles(String text) {
        String normalized = normalizeForFingerprint(text);
        Set<String> shingles = new HashSet<>();
        if (normalized.isEmpty()) {
            return shingles;
        }
        if (normalized.length() <= 4) {
            shingles.add(normalized);
            return shingles;
        }
        for (int i = 0; i <= normalized.length() - 4; i++) {
            shingles.add(normalized.substring(i, i + 4));
        }
        return shingles;
    }

    private String buildFingerprint(String summary, List<WebSearchService.SearchResult> results) {
        StringBuilder raw = new StringBuilder(normalizeForFingerprint(summary));
        if (results != null) {
            int limit = Math.min(results.size(), 3);
            for (int i = 0; i < limit; i++) {
                WebSearchService.SearchResult result = results.get(i);
                raw.append('|').append(normalizeForFingerprint(result.source));
                raw.append('|').append(normalizeForFingerprint(result.url));
                raw.append('|').append(normalizeForFingerprint(truncate(result.snippet, 120)));
            }
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] bytes = digest.digest(raw.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(raw.toString().hashCode());
        }
    }

    private Integer inferTargetYear(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(20\\d{2})").matcher(text);
        if (matcher.find()) {
            try {
                return Integer.parseInt(matcher.group(1));
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private String resolveRefreshMode(String refreshMode) {
        String normalized = firstNonBlank(trimToNull(refreshMode), "DAILY").toUpperCase();
        if (!"MANUAL".equals(normalized) && !"INTERVAL".equals(normalized)) {
            return "DAILY";
        }
        return normalized;
    }

    private int resolveRefreshIntervalHours(Integer refreshIntervalHours, String refreshMode) {
        if ("DAILY".equalsIgnoreCase(refreshMode)) {
            return 24;
        }
        int value = refreshIntervalHours == null ? 24 : refreshIntervalHours;
        return Math.max(1, Math.min(value, 24 * 14));
    }

    private int resolveAutoRefreshEnabled(Boolean autoRefreshEnabled, String refreshMode) {
        if ("MANUAL".equalsIgnoreCase(firstNonBlank(refreshMode, ""))) {
            return 0;
        }
        return Boolean.FALSE.equals(autoRefreshEnabled) ? 0 : 1;
    }

    private String resolveReopenPolicy(String reopenPolicy, boolean linkedMeeting) {
        String normalized = firstNonBlank(trimToNull(reopenPolicy), linkedMeeting ? "SUGGEST" : "MANUAL").toUpperCase();
        if (!linkedMeeting) {
            return "MANUAL";
        }
        if (!"AUTO".equals(normalized) && !"SUGGEST".equals(normalized)) {
            return "MANUAL";
        }
        return normalized;
    }

    private AiMeetingTrackingItem requireItem(Long trackingItemId) {
        AiMeetingTrackingItem item = trackingItemMapper.selectById(trackingItemId);
        if (item == null) {
            throw new IllegalArgumentException("追踪项不存在: " + trackingItemId);
        }
        return item;
    }

    private String normalizeForFingerprint(String text) {
        return text == null ? "" : text.replaceAll("\\s+", "").trim().toLowerCase();
    }

    private String truncate(String text, int maxLen) {
        if (text == null) {
            return null;
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= maxLen) {
            return normalized;
        }
        return normalized.substring(0, maxLen) + "…";
    }

    private String trimToNull(String text) {
        if (text == null) {
            return null;
        }
        String normalized = text.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private String firstNonBlank(String primary, String fallback) {
        return StringUtils.hasText(primary) ? primary : fallback;
    }
}
