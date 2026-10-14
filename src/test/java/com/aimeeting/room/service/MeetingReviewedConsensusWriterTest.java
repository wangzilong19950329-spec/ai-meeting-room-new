package com.aimeeting.room.service;

import com.aimeeting.room.dao.AiMeetingConclusionClusterMapper;
import com.aimeeting.room.dao.AiMeetingRoomMapper;
import com.aimeeting.room.dao.AiMeetingVoteMapper;
import com.aimeeting.room.entity.AiMeetingConclusionCluster;
import com.aimeeting.room.entity.AiMeetingRoom;
import com.aimeeting.room.entity.AiMeetingVote;
import com.aimeeting.room.enums.MeetingStatusEnum;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = "spring.main.web-application-type=none")
@ActiveProfiles("smoketest")
class MeetingReviewedConsensusWriterTest {
    @Autowired private AiMeetingRoomMapper rooms;
    @Autowired private AiMeetingVoteMapper votes;
    @Autowired private AiMeetingConclusionClusterMapper clusters;
    @Autowired private MeetingReviewedConsensusWriter writer;

    @Test
    void pauseResumeFencesLateVotesAndClustersButRetainsCompletedCheckpoints() {
        AiMeetingRoom room = new AiMeetingRoom();
        room.setTitle("审核投票与聚类代次保护测试");
        room.setCreatorId(1L);
        room.setStatus(MeetingStatusEnum.IN_PROGRESS.getCode());
        room.setMaxRounds(1);
        room.setCurrentRound(1);
        rooms.insert(room);
        long id = room.getId();
        try {
            assertTrue(writer.insertVoteIfCurrent(vote(id, 3L, 1), 0L));
            assertEquals(1, votes.selectByMeetingIdAndRound(id, 1).size());
            assertEquals(1, rooms.updateStatusIfCurrentAndEpoch(id,
                    MeetingStatusEnum.IN_PROGRESS.getCode(), 0L, MeetingStatusEnum.PAUSED.getCode()));
            assertThrows(MeetingReviewedEvidenceService.EvidenceRejected.class,
                    () -> writer.insertVoteIfCurrent(vote(id, 4L, 1), 0L));
            assertThrows(MeetingReviewedEvidenceService.EvidenceRejected.class,
                    () -> writer.insertClustersIfCurrent(id, 1, List.of(cluster(id, 1)), 0L));
            assertEquals(1, rooms.claimReviewedRun(id, MeetingStatusEnum.PAUSED.getCode(), 1L,
                    MeetingStatusEnum.IN_PROGRESS.getCode()));
            assertThrows(MeetingReviewedEvidenceService.EvidenceRejected.class,
                    () -> writer.insertVoteIfCurrent(vote(id, 4L, 1), 0L));
            assertThrows(MeetingReviewedEvidenceService.EvidenceRejected.class,
                    () -> writer.insertClustersIfCurrent(id, 1, List.of(cluster(id, 1)), 0L));
            assertFalse(writer.insertVoteIfCurrent(vote(id, 3L, 1), 2L));
            assertTrue(writer.insertVoteIfCurrent(vote(id, 4L, 1), 2L));
            assertTrue(writer.insertClustersIfCurrent(id, 1, List.of(cluster(id, 1)), 2L));
            assertFalse(writer.insertClustersIfCurrent(id, 1, List.of(cluster(id, 1)), 2L));
            assertEquals(2, votes.selectByMeetingIdAndRound(id, 1).size());
            assertEquals(1, clusters.selectByMeetingIdAndRound(id, 1).size());
        } finally {
            clusters.deleteByMeetingId(id);
            votes.deleteByMeetingId(id);
            rooms.deleteById(id);
        }
    }

    @Test
    void anotherRoundCannotReceiveCurrentRunResults() {
        AiMeetingRoom room = new AiMeetingRoom();
        room.setTitle("审核轮次保护测试");
        room.setCreatorId(1L);
        room.setStatus(MeetingStatusEnum.IN_PROGRESS.getCode());
        room.setMaxRounds(2);
        room.setCurrentRound(2);
        rooms.insert(room);
        long id = room.getId();
        try {
            assertThrows(MeetingReviewedEvidenceService.EvidenceRejected.class,
                    () -> writer.insertVoteIfCurrent(vote(id, 3L, 1), 0L));
            assertThrows(MeetingReviewedEvidenceService.EvidenceRejected.class,
                    () -> writer.insertClustersIfCurrent(id, 1, List.of(cluster(id, 1)), 0L));
            assertEquals(0, votes.selectByMeetingIdAndRound(id, 1).size());
            assertEquals(0, clusters.selectByMeetingIdAndRound(id, 1).size());
        } finally {
            clusters.deleteByMeetingId(id);
            votes.deleteByMeetingId(id);
            rooms.deleteById(id);
        }
    }

    private static AiMeetingVote vote(long meetingId, long memberId, int roundNumber) {
        AiMeetingVote vote = new AiMeetingVote();
        vote.setMeetingId(meetingId);
        vote.setMemberId(memberId);
        vote.setRoundNumber(roundNumber);
        vote.setVotePosition("支持");
        vote.setConfidence(new BigDecimal("0.80"));
        return vote;
    }

    private static AiMeetingConclusionCluster cluster(long meetingId, int roundNumber) {
        AiMeetingConclusionCluster cluster = new AiMeetingConclusionCluster();
        cluster.setMeetingId(meetingId);
        cluster.setRoundNumber(roundNumber);
        cluster.setClusterLabel("支持");
        cluster.setMemberCount(2);
        cluster.setMemberIds("3,4");
        cluster.setAgreementRatio(new BigDecimal("1.00"));
        cluster.setIsConsensus(1);
        return cluster;
    }
}
