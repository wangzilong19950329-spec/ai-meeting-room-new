package com.aimeeting.room.service;

import com.aimeeting.room.dao.AiMeetingConclusionClusterMapper;
import com.aimeeting.room.dao.AiMeetingRoomMapper;
import com.aimeeting.room.dao.AiMeetingVoteMapper;
import com.aimeeting.room.entity.AiMeetingConclusionCluster;
import com.aimeeting.room.entity.AiMeetingRoom;
import com.aimeeting.room.entity.AiMeetingVote;
import com.aimeeting.room.enums.MeetingStatusEnum;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/** Commits reviewed voting results under the same row lock used by meeting state transitions. */
@Service
@RequiredArgsConstructor
public class MeetingReviewedConsensusWriter {
    private final AiMeetingRoomMapper roomMapper;
    private final AiMeetingVoteMapper voteMapper;
    private final AiMeetingConclusionClusterMapper clusterMapper;

    @Transactional
    public boolean insertVoteIfCurrent(AiMeetingVote vote, long expectedEpoch) {
        lockAndCheck(vote.getMeetingId(), vote.getRoundNumber(), expectedEpoch);
        if (voteMapper.selectByMeetingMemberRound(vote.getMeetingId(), vote.getMemberId(),
                vote.getRoundNumber()) != null) {
            return false;
        }
        voteMapper.insert(vote);
        return true;
    }

    @Transactional
    public boolean insertClustersIfCurrent(Long meetingId, int roundNumber,
                                           List<AiMeetingConclusionCluster> clusters, long expectedEpoch) {
        lockAndCheck(meetingId, roundNumber, expectedEpoch);
        if (clusters == null || clusters.isEmpty()) return false;
        for (AiMeetingConclusionCluster cluster : clusters) {
            if (!Objects.equals(meetingId, cluster.getMeetingId())
                    || !Objects.equals(roundNumber, cluster.getRoundNumber())) {
                throw new IllegalArgumentException("cluster meeting/round mismatch");
            }
        }
        List<AiMeetingConclusionCluster> existing = clusterMapper.selectByMeetingIdAndRound(meetingId, roundNumber);
        if (existing != null && !existing.isEmpty()) return false;
        clusterMapper.batchInsert(clusters);
        return true;
    }

    private void lockAndCheck(Long meetingId, int roundNumber, long expectedEpoch) {
        AiMeetingRoom room = roomMapper.selectByIdForUpdate(meetingId);
        boolean active = room != null && (Objects.equals(room.getStatus(), MeetingStatusEnum.IN_PROGRESS.getCode())
                || Objects.equals(room.getStatus(), MeetingStatusEnum.REOPENED.getCode()));
        if (!active || !Objects.equals(room.getRunEpoch(), expectedEpoch)
                || !Objects.equals(room.getCurrentRound(), roundNumber)) {
            throw new MeetingReviewedEvidenceService.EvidenceRejected("MEETING_RUN_STALE");
        }
    }
}
