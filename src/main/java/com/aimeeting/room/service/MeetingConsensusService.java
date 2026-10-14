package com.aimeeting.room.service;

import com.aimeeting.room.config.AiModelProperties;
import com.aimeeting.room.dao.*;
import com.aimeeting.room.dto.ConsensusResult;
import com.aimeeting.room.entity.*;
import com.aimeeting.room.enums.OpinionTypeEnum;
import com.aimeeting.room.service.ai.WebSearchService;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
public class MeetingConsensusService {

    @Autowired
    private AiMeetingVoteMapper voteMapper;

    @Autowired
    private AiMeetingOpinionMapper opinionMapper;

    @Autowired
    private AiMeetingMemberMapper memberMapper;

    @Autowired
    private AiMeetingRoomMapper roomMapper;

    @Autowired
    private AiMeetingConclusionClusterMapper clusterMapper;

    @Autowired
    private MeetingOrchestratorService orchestrator;

    @Autowired(required = false)
    private MeetingReviewedEvidenceService reviewedEvidenceService;

    @Autowired
    private MeetingReviewedConsensusWriter reviewedConsensusWriter;

    @Autowired
    private AiModelProperties aiModelProperties;

    /** 投票：每位成员表明当前立场 */
    public void conductVoting(Long meetingId, int roundNumber) {
        conductVoting(meetingId, roundNumber, null);
    }

    /** 审核会议写入时必须携带启动时捕获的运行代次。 */
    public void conductVoting(Long meetingId, int roundNumber, Long expectedEpoch) {
        List<AiMeetingMember> members = memberMapper.selectByMeetingId(meetingId);
        boolean reviewed = reviewedEvidenceService != null && reviewedEvidenceService.isEnabled();
        if (reviewed && expectedEpoch == null) {
            throw new MeetingReviewedEvidenceService.EvidenceRejected("MEETING_RUN_EPOCH_REQUIRED");
        }
        List<AiMeetingVote> existing = reviewed ? voteMapper.selectByMeetingIdAndRound(meetingId, roundNumber)
                : java.util.Collections.emptyList();
        Set<Long> eligibleSpeakers = reviewed ? reviewedEligibleSpeakerIds(meetingId, roundNumber)
                : Collections.emptySet();
        if (reviewed) validatedVoteIds(existing, eligibleSpeakers);
        for (AiMeetingMember member : members) {
            if (reviewed && !eligibleSpeakers.contains(member.getId())) continue;
            if (reviewed && existing.stream().anyMatch(vote -> member.getId().equals(vote.getMemberId()))) continue;
            String prompt = orchestrator.buildVotePrompt(meetingId, member.getId());
            String systemPrompt = "你是会议成员「" + member.getMemberName() + "」，请表明你的最终立场。";
            if (reviewed && hasLowConfidenceStatement(meetingId, roundNumber)) {
                systemPrompt += "\n本轮有议员依据未充分交叉核验的材料作条件分析。"
                        + "投票只能依据已核验事实或明确标注的条件判断；不要把本人或其他议员的待核验数字、反证候选当成已证实依据。"
                        + "对证据不足的结论明确保留条件。";
            }
            String primaryModelType = reviewed ? member.getModelType()
                    : orchestrator.resolveNonSearchModelType(member.getModelType());
            String response;
            try {
                response = reviewed
                        ? orchestrator.callModelStrict(primaryModelType, systemPrompt, prompt, null)
                        : orchestrator.callModel(primaryModelType, systemPrompt, prompt);
            } catch (RuntimeException modelFailure) {
                if (!reviewed) throw modelFailure;
                log.warn("Reviewed vote unavailable for member {} in meeting {} round {} ({})",
                        member.getId(), meetingId, roundNumber, modelFailure.getClass().getSimpleName());
                continue;
            }
            if (!reviewed && (response == null || response.isBlank())) {
                String fallbackModelType = orchestrator.resolveOperationalModelType(member.getModelType());
                if (fallbackModelType != null && !fallbackModelType.isBlank() && !fallbackModelType.equals(primaryModelType)) {
                    log.warn("Vote generation fallback for member {} from {} to {}",
                            member.getMemberName(), primaryModelType, fallbackModelType);
                    response = orchestrator.callModel(fallbackModelType, systemPrompt, prompt);
                }
            }
            if (response == null || response.isBlank()) {
                if (reviewed) log.warn("Reviewed vote output unavailable for member {} in meeting {} round {}",
                        member.getId(), meetingId, roundNumber);
                continue;
            }

            BigDecimal confidence = new BigDecimal("0.50");
            String position = response;
            String reasoning = "";
            try {
                String json = extractJson(response, "{");
                JSONObject obj = JSON.parseObject(json);
                position = obj.getString("position");
                confidence = obj.getBigDecimal("confidence");
                reasoning = obj.getString("reasoning");
                if (reviewed && (position == null || position.isBlank() || confidence == null)) {
                    throw new IllegalArgumentException("vote position/confidence missing");
                }
            } catch (Exception e) {
                if (reviewed) {
                    log.warn("Reviewed vote parse failed for member {} in meeting {} round {}",
                            member.getId(), meetingId, roundNumber);
                    continue;
                }
                log.warn("Vote parse failed for member={}, using raw response", member.getId());
            }

            AiMeetingVote vote = new AiMeetingVote();
            vote.setMeetingId(meetingId);
            vote.setMemberId(member.getId());
            vote.setRoundNumber(roundNumber);
            vote.setVotePosition(position != null ? position : response);
            vote.setConfidence(confidence);
            vote.setReasoning(reasoning);
            if (reviewed) {
                reviewedConsensusWriter.insertVoteIfCurrent(vote, expectedEpoch);
            } else {
                voteMapper.insert(vote);
            }
        }
    }

    /** A reviewed ballot requires a persisted statement produced from an accepted evidence path. */
    public Set<Long> reviewedEligibleSpeakerIds(Long meetingId, int roundNumber) {
        Set<Long> registered = memberMapper.selectByMeetingId(meetingId).stream()
                .map(AiMeetingMember::getId).filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<AiMeetingOpinion> opinions = opinionMapper.selectByMeetingIdAndRound(meetingId, roundNumber);
        if (opinions == null || opinions.isEmpty()) return Collections.emptySet();
        Set<Long> eligible = new LinkedHashSet<>();
        for (AiMeetingOpinion opinion : opinions) {
            if (opinion == null || !registered.contains(opinion.getMemberId())
                    || !Objects.equals(opinion.getOpinionType(), OpinionTypeEnum.STATEMENT.getCode())
                    || opinion.getEvidence() == null || opinion.getEvidence().isBlank()) continue;
            try {
                JSONObject evidence = JSON.parseObject(opinion.getEvidence());
                String status = evidence == null ? null : evidence.getString("reviewedSearchStatus");
                if (evidence != null && ("UNSEARCHED_BUDGET".equals(
                        evidence.getString("reviewedSearchCoverageStatus"))
                        || "UNVERIFIED_GAP".equals(
                        evidence.getString("reviewedSearchCoverageStatus")))
                        && !"LOW_CONFIDENCE".equals(status)) continue;
                // A provisional-only speech is allowed to surface uncertainty, but it
                // cannot grant a ballot without a separately validated fact.
                if ("LOW_CONFIDENCE".equals(status)
                        && !hasVerifiedFactForLimitedCoverage(evidence)) continue;
                if ("CROSS_CHECKED".equals(status) || "LOW_CONFIDENCE".equals(status)
                        || "STRUCTURED_ONLY".equals(status)
                        || "NO_SEARCH_NEEDED".equals(status)) {
                    eligible.add(opinion.getMemberId());
                }
            } catch (RuntimeException malformedEvidence) {
                // Historical or malformed evidence never grants a reviewed ballot.
            }
        }
        return Collections.unmodifiableSet(eligible);
    }

    private static boolean hasVerifiedFactForLimitedCoverage(JSONObject evidence) {
        JSONArray stored = evidence.getJSONArray("results");
        if (stored == null || stored.isEmpty()) return false;
        for (int i = 0; i < stored.size(); i++) {
            JSONObject result = stored.getJSONObject(i);
            if (result != null && "eastmoney".equals(result.getString("provider"))
                    && "STRUCTURED_DATA".equals(result.getString("sourceType"))
                    && result.getString("query") != null
                    && result.getString("query").startsWith("EASTMONEY:")
                    && result.getString("url") != null
                    && result.getString("url").startsWith("https://quote.eastmoney.com/")
                    && result.getString("rawHash") != null
                    && !result.getString("rawHash").isBlank()) return true;
        }
        JSONArray packets = evidence.getJSONArray("reviewedSearchPackets");
        if (packets == null) {
            packets = new JSONArray();
            JSONObject single = evidence.getJSONObject("reviewedSearchPacket");
            if (single != null) packets.add(single);
        }
        for (int i = 0; i < packets.size(); i++) {
            JSONObject packet = packets.getJSONObject(i);
            if (packet == null || packet.getJSONArray("claims") == null
                    || packet.getJSONArray("claims").isEmpty()
                    || !Objects.equals(evidence.getString("reviewedSearchAsOf"),
                        packet.getString("asOf"))) continue;
            try {
                MeetingReviewedEvidenceService.ReviewedEvidence accepted =
                        MeetingReviewedEvidenceService.acceptPacket(packet,
                                packet.getString("requestId"), packet.getString("question"));
                for (WebSearchService.SearchResult verified : accepted.results) {
                    if (!"n8n-deepseek-reviewed".equals(verified.provider)
                            || !"CROSS_CHECKED_SEARCH".equals(verified.sourceType)
                            || verified.rawHash == null) continue;
                    for (int j = 0; j < stored.size(); j++) {
                        JSONObject result = stored.getJSONObject(j);
                        if (result != null && Objects.equals(verified.provider, result.getString("provider"))
                                && Objects.equals(verified.sourceType, result.getString("sourceType"))
                                && Objects.equals(verified.url, result.getString("url"))
                                && Objects.equals(verified.query, result.getString("query"))
                                && Objects.equals(verified.rawHash, result.getString("rawHash"))) return true;
                    }
                }
            } catch (RuntimeException malformedPacket) {
                // An invalid historical packet cannot grant voting rights.
            }
        }
        return false;
    }

    private boolean hasLowConfidenceStatement(Long meetingId, int roundNumber) {
        List<AiMeetingOpinion> opinions = opinionMapper.selectByMeetingIdAndRound(meetingId, roundNumber);
        if (opinions == null) return false;
        for (AiMeetingOpinion opinion : opinions) {
            if (opinion == null || !Objects.equals(opinion.getOpinionType(), OpinionTypeEnum.STATEMENT.getCode())
                    || opinion.getEvidence() == null) continue;
            try {
                JSONObject evidence = JSON.parseObject(opinion.getEvidence());
                if (evidence != null && "LOW_CONFIDENCE".equals(evidence.getString("reviewedSearchStatus"))) {
                    return true;
                }
            } catch (RuntimeException ignored) {
                // A malformed historical opinion never changes the reviewed vote prompt.
            }
        }
        return false;
    }

    private static Set<Long> validatedVoteIds(List<AiMeetingVote> votes, Set<Long> eligibleSpeakers) {
        Set<Long> ids = new LinkedHashSet<>();
        if (votes == null) return ids;
        for (AiMeetingVote vote : votes) {
            if (vote == null || !eligibleSpeakers.contains(vote.getMemberId())
                    || !ids.add(vote.getMemberId())) {
                throw new MeetingReviewedEvidenceService.EvidenceRejected("REVIEWED_VOTE_MEMBER_INVALID");
            }
        }
        return ids;
    }

    /** 对投票结果进行语义聚类 */
    public void clusterVotePositions(Long meetingId, int roundNumber) {
        clusterVotePositions(meetingId, roundNumber, null);
    }

    /** 审核会议写入时必须携带启动时捕获的运行代次。 */
    public void clusterVotePositions(Long meetingId, int roundNumber, Long expectedEpoch) {
        boolean reviewed = reviewedEvidenceService != null && reviewedEvidenceService.isEnabled();
        if (reviewed && expectedEpoch == null) {
            throw new MeetingReviewedEvidenceService.EvidenceRejected("MEETING_RUN_EPOCH_REQUIRED");
        }
        List<AiMeetingMember> members = reviewed ? memberMapper.selectByMeetingId(meetingId) : null;
        int totalMembers = reviewed ? members.size() : memberMapper.countByMeetingId(meetingId);
        Set<Long> voteIds = Collections.emptySet();
        if (reviewed) {
            voteIds = validatedVoteIds(voteMapper.selectByMeetingIdAndRound(meetingId, roundNumber),
                    reviewedEligibleSpeakerIds(meetingId, roundNumber));
            if (voteIds.isEmpty()) throw new MeetingReviewedEvidenceService.EvidenceRejected("REVIEWED_VOTES_MISSING");
        }
        AiModelProperties.MeetingConfig cfg = aiModelProperties.getMeeting();
        int required = Math.max(cfg.getMinAgreementCount(),
                (int) Math.ceil(totalMembers * cfg.getConsensusThreshold()));
        if (reviewed) {
            List<AiMeetingConclusionCluster> existingClusters = clusterMapper.selectByMeetingIdAndRound(meetingId, roundNumber);
            if (existingClusters != null && !existingClusters.isEmpty()) {
                validatePersistedClusters(existingClusters, voteIds, totalMembers, required);
                return;
            }
        }
        String meetingModelType = reviewed ? orchestrator.resolveMeetingModelType(meetingId)
                : orchestrator.resolveOperationalMeetingModelType(meetingId);

        String prompt = orchestrator.buildClusterPrompt(meetingId, roundNumber);
        String response = reviewed
                ? orchestrator.callModelStrict(meetingModelType,
                    "你是文本聚类专家，请对以下投票立场进行语义聚类，相似观点归为同一组。", prompt, 800)
                : orchestrator.callModel(meetingModelType,
                    "你是文本聚类专家，请对以下投票立场进行语义聚类，相似观点归为同一组。", prompt, 800);
        if (response == null || response.isBlank()) {
            if (reviewed) throw new IllegalStateException("meeting_cluster_output_unavailable");
            return;
        }

        List<AiMeetingConclusionCluster> toInsert = new ArrayList<>();
        try {
            String jsonStr = extractJson(response, "[");
            JSONArray clusters = JSON.parseArray(jsonStr);
            if (clusters == null || clusters.isEmpty()) throw new IllegalArgumentException("clusters missing");
            Set<Long> seen = new HashSet<>();
            for (int i = 0; i < clusters.size(); i++) {
                JSONObject c = clusters.getJSONObject(i);
                String label = c.getString("clusterLabel");
                JSONArray ids = c.getJSONArray("memberIds");
                if (reviewed && (label == null || label.isBlank() || ids == null || ids.isEmpty())) {
                    throw new IllegalArgumentException("cluster label or IDs missing");
                }
                List<Long> memberIds = new ArrayList<>();
                for (int j = 0; j < ids.size(); j++) {
                    Long id = ids.getLong(j);
                    if (reviewed && (id == null || !voteIds.contains(id) || !seen.add(id))) {
                        throw new IllegalArgumentException("cluster member not a unique valid voter");
                    }
                    memberIds.add(id);
                }

                int count = memberIds.size();
                BigDecimal ratio = new BigDecimal(count)
                        .divide(new BigDecimal(totalMembers), 2, RoundingMode.HALF_UP);

                AiMeetingConclusionCluster cluster = new AiMeetingConclusionCluster();
                cluster.setMeetingId(meetingId);
                cluster.setRoundNumber(roundNumber);
                cluster.setClusterLabel(label);
                cluster.setMemberCount(count);
                cluster.setMemberIds(memberIds.stream().map(String::valueOf).collect(Collectors.joining(",")));
                cluster.setAgreementRatio(ratio);
                cluster.setIsConsensus(count >= required ? 1 : 0);
                toInsert.add(cluster);
            }
            if (reviewed && !seen.equals(voteIds)) {
                throw new IllegalArgumentException("cluster omits a valid voter");
            }
        } catch (Exception e) {
            if (reviewed) throw new IllegalStateException("meeting_cluster_parse_failed", e);
            log.error("Cluster parse failed, meetingId={}, round={}", meetingId, roundNumber, e);
            return;
        }
        if (reviewed) {
            if (toInsert.isEmpty()) throw new IllegalStateException("meeting_cluster_empty");
            reviewedConsensusWriter.insertClustersIfCurrent(meetingId, roundNumber, toInsert, expectedEpoch);
        } else if (!toInsert.isEmpty()) {
            clusterMapper.batchInsert(toInsert);
        }
    }

    /** 检查是否达成共识 */
    public ConsensusResult checkConsensus(Long meetingId, int roundNumber) {
        boolean reviewed = reviewedEvidenceService != null && reviewedEvidenceService.isEnabled();
        if (reviewed) {
            List<AiMeetingConclusionCluster> clusters = clusterMapper.selectByMeetingIdAndRound(meetingId, roundNumber);
            Set<Long> voteIds = validatedVoteIds(voteMapper.selectByMeetingIdAndRound(meetingId, roundNumber),
                    reviewedEligibleSpeakerIds(meetingId, roundNumber));
            List<Long> allIds = memberMapper.selectByMeetingId(meetingId).stream()
                    .map(AiMeetingMember::getId).collect(Collectors.toList());
            if (clusters == null || clusters.isEmpty()) {
                ConsensusResult result = ConsensusResult.notReached();
                result.setAbsentMemberIds(absentIds(allIds, voteIds));
                return result;
            }
            AiModelProperties.MeetingConfig cfg = aiModelProperties.getMeeting();
            int required = Math.max(cfg.getMinAgreementCount(),
                    (int) Math.ceil(allIds.size() * cfg.getConsensusThreshold()));
            validatePersistedClusters(clusters, voteIds, allIds.size(), required);
            for (AiMeetingConclusionCluster cluster : clusters) {
                if (Objects.equals(cluster.getIsConsensus(), 1)) {
                    return reviewedResult(cluster, voteIds, allIds, true);
                }
            }
            ConsensusResult result = ConsensusResult.notReached();
            result.setAbsentMemberIds(absentIds(allIds, voteIds));
            return result;
        }
        AiMeetingConclusionCluster consensus = clusterMapper.selectConsensusCluster(meetingId, roundNumber);
        if (consensus != null) {
            List<Long> agreeIds = parseIds(consensus.getMemberIds());
            List<Long> allIds = memberMapper.selectByMeetingId(meetingId).stream()
                    .map(AiMeetingMember::getId).collect(Collectors.toList());
            List<Long> dissentIds = allIds.stream().filter(id -> !agreeIds.contains(id)).collect(Collectors.toList());
            return ConsensusResult.reached(consensus.getClusterLabel(), consensus.getAgreementRatio(), agreeIds, dissentIds);
        }
        return ConsensusResult.notReached();
    }

    /** 强制结论：取最大聚类 */
    public ConsensusResult forceConclude(Long meetingId) {
        int lastRound = roomMapper.selectById(meetingId).getCurrentRound();
        List<AiMeetingConclusionCluster> clusters = clusterMapper.selectByMeetingIdAndRound(meetingId, lastRound);
        boolean reviewed = reviewedEvidenceService != null && reviewedEvidenceService.isEnabled();
        if (reviewed) {
            Set<Long> voteIds = validatedVoteIds(voteMapper.selectByMeetingIdAndRound(meetingId, lastRound),
                    reviewedEligibleSpeakerIds(meetingId, lastRound));
            List<Long> allIds = memberMapper.selectByMeetingId(meetingId).stream()
                    .map(AiMeetingMember::getId).collect(Collectors.toList());
            if (clusters == null || clusters.isEmpty()) {
                ConsensusResult result = ConsensusResult.notReached();
                result.setAbsentMemberIds(absentIds(allIds, voteIds));
                return result;
            }
            AiModelProperties.MeetingConfig cfg = aiModelProperties.getMeeting();
            int required = Math.max(cfg.getMinAgreementCount(),
                    (int) Math.ceil(allIds.size() * cfg.getConsensusThreshold()));
            validatePersistedClusters(clusters, voteIds, allIds.size(), required);
            if (clusters.size() > 1
                    && Objects.equals(clusters.get(0).getMemberCount(), clusters.get(1).getMemberCount())
                    && !Objects.equals(clusters.get(0).getIsConsensus(), 1)) {
                int largestCount = clusters.get(0).getMemberCount();
                String tiedLabels = clusters.stream()
                        .filter(cluster -> Objects.equals(cluster.getMemberCount(), largestCount))
                        .map(AiMeetingConclusionCluster::getClusterLabel)
                        .collect(Collectors.joining("；"));
                ConsensusResult tied = ConsensusResult.partial(
                        "未达成一致，得票最高观点并列：" + tiedLabels,
                        clusters.get(0).getAgreementRatio(), Collections.emptyList(), Collections.emptyList());
                tied.setAbsentMemberIds(absentIds(allIds, voteIds));
                return tied;
            }
            return reviewedResult(clusters.get(0), voteIds, allIds,
                    Objects.equals(clusters.get(0).getIsConsensus(), 1));
        }
        if (clusters.isEmpty()) return ConsensusResult.notReached();

        AiMeetingConclusionCluster largest = clusters.get(0);
        List<Long> agreeIds = parseIds(largest.getMemberIds());
        List<Long> allIds = memberMapper.selectByMeetingId(meetingId).stream()
                .map(AiMeetingMember::getId).collect(Collectors.toList());
        List<Long> dissentIds = allIds.stream().filter(id -> !agreeIds.contains(id)).collect(Collectors.toList());
        if (largest.getIsConsensus() != null && largest.getIsConsensus() == 1) {
            return ConsensusResult.reached(largest.getClusterLabel(), largest.getAgreementRatio(), agreeIds, dissentIds);
        }
        String forcedConclusion = buildForcedConclusionLabel(largest);
        return ConsensusResult.partial(forcedConclusion, largest.getAgreementRatio(), agreeIds, dissentIds);
    }

    private ConsensusResult reviewedResult(AiMeetingConclusionCluster cluster, Set<Long> voteIds,
                                           List<Long> allIds, boolean consensus) {
        List<Long> agreeIds = parseIds(cluster.getMemberIds());
        List<Long> dissentIds = voteIds.stream().filter(id -> !agreeIds.contains(id))
                .collect(Collectors.toList());
        ConsensusResult result = consensus
                ? ConsensusResult.reached(cluster.getClusterLabel(), cluster.getAgreementRatio(), agreeIds, dissentIds)
                : ConsensusResult.partial(buildForcedConclusionLabel(cluster), cluster.getAgreementRatio(),
                        agreeIds, dissentIds);
        result.setAbsentMemberIds(absentIds(allIds, voteIds));
        return result;
    }

    private static List<Long> absentIds(List<Long> allIds, Set<Long> voteIds) {
        return allIds.stream().filter(id -> !voteIds.contains(id)).collect(Collectors.toList());
    }

    private static void validatePersistedClusters(List<AiMeetingConclusionCluster> clusters,
                                                  Set<Long> voteIds, int totalMembers, int required) {
        if (totalMembers <= 0 || voteIds.isEmpty()) {
            throw new MeetingReviewedEvidenceService.EvidenceRejected("REVIEWED_CLUSTER_VOTES_MISSING");
        }
        Set<Long> seen = new HashSet<>();
        for (AiMeetingConclusionCluster cluster : clusters) {
            if (cluster == null || cluster.getClusterLabel() == null || cluster.getClusterLabel().isBlank()
                    || cluster.getMemberIds() == null || cluster.getMemberIds().isBlank()) {
                throw new MeetingReviewedEvidenceService.EvidenceRejected("REVIEWED_CLUSTER_INVALID");
            }
            List<Long> ids;
            try {
                ids = Arrays.stream(cluster.getMemberIds().split(","))
                        .map(Long::parseLong).collect(Collectors.toList());
            } catch (RuntimeException malformed) {
                throw new MeetingReviewedEvidenceService.EvidenceRejected("REVIEWED_CLUSTER_INVALID");
            }
            for (Long id : ids) {
                if (!voteIds.contains(id) || !seen.add(id)) {
                    throw new MeetingReviewedEvidenceService.EvidenceRejected("REVIEWED_CLUSTER_MEMBER_INVALID");
                }
            }
            BigDecimal ratio = BigDecimal.valueOf(ids.size())
                    .divide(BigDecimal.valueOf(totalMembers), 2, RoundingMode.HALF_UP);
            if (!Objects.equals(cluster.getMemberCount(), ids.size())
                    || cluster.getAgreementRatio() == null || cluster.getAgreementRatio().compareTo(ratio) != 0
                    || !Objects.equals(cluster.getIsConsensus(), ids.size() >= required ? 1 : 0)) {
                throw new MeetingReviewedEvidenceService.EvidenceRejected("REVIEWED_CLUSTER_COUNT_INVALID");
            }
        }
        if (!seen.equals(voteIds)) {
            throw new MeetingReviewedEvidenceService.EvidenceRejected("REVIEWED_CLUSTER_VOTER_MISSING");
        }
    }

    private String buildForcedConclusionLabel(AiMeetingConclusionCluster largest) {
        String label = largest.getClusterLabel() != null ? largest.getClusterLabel().trim() : "暂无主导观点";
        String ratioText = largest.getAgreementRatio() != null
                ? largest.getAgreementRatio().stripTrailingZeros().toPlainString()
                : "0";
        return "未达成一致，当前占优观点为：" + label + "（支持比例" + ratioText + "）";
    }

    private List<Long> parseIds(String memberIds) {
        return Arrays.stream(memberIds.split(",")).map(Long::parseLong).collect(Collectors.toList());
    }

    private String extractJson(String text, String startChar) {
        int start = text.indexOf(startChar);
        String endChar = startChar.equals("[") ? "]" : "}";
        int end = text.lastIndexOf(endChar);
        return (start >= 0 && end > start) ? text.substring(start, end + 1) : text;
    }
}
