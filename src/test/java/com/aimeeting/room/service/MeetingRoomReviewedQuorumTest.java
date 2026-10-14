package com.aimeeting.room.service;

import com.aimeeting.room.dao.AiMeetingMemberMapper;
import com.aimeeting.room.dao.AiMeetingVoteMapper;
import com.aimeeting.room.entity.AiMeetingVote;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MeetingRoomReviewedQuorumTest {

    @Test
    void oneUnavailableMemberDoesNotStopTwoReviewedSpeakersOrVotes() {
        Fixture fixture = new Fixture(3, Set.of(10L, 11L), List.of(vote(10L), vote(11L)));
        assertDoesNotThrow(() -> fixture.check(false));
        assertDoesNotThrow(() -> fixture.check(true));
    }

    @Test
    void onlyOneReviewedSpeakerCannotFormAThreeMemberDecision() {
        Fixture fixture = new Fixture(3, Set.of(10L), List.of());
        assertThrows(MeetingReviewedEvidenceService.EvidenceRejected.class,
                () -> fixture.check(false));
    }

    @Test
    void MissingBallotCannotFormAThreeMemberDecision() {
        Fixture fixture = new Fixture(3, Set.of(10L, 11L), List.of(vote(10L)));
        assertThrows(MeetingReviewedEvidenceService.EvidenceRejected.class,
                () -> fixture.check(true));
    }

    private static AiMeetingVote vote(long memberId) {
        AiMeetingVote vote = new AiMeetingVote();
        vote.setMemberId(memberId);
        return vote;
    }

    private static final class Fixture {
        private final MeetingRoomService room = new MeetingRoomService();

        private Fixture(int memberCount, Set<Long> eligible, List<AiMeetingVote> votes) {
            AiMeetingMemberMapper members = mock(AiMeetingMemberMapper.class);
            MeetingConsensusService consensus = mock(MeetingConsensusService.class);
            AiMeetingVoteMapper ballots = mock(AiMeetingVoteMapper.class);
            when(members.countByMeetingId(1L)).thenReturn(memberCount);
            when(consensus.reviewedEligibleSpeakerIds(1L, 1)).thenReturn(eligible);
            when(ballots.selectByMeetingIdAndRound(1L, 1)).thenReturn(votes);
            ReflectionTestUtils.setField(room, "memberMapper", members);
            ReflectionTestUtils.setField(room, "consensusService", consensus);
            ReflectionTestUtils.setField(room, "voteMapper", ballots);
        }

        private void check(boolean afterVoting) {
            ReflectionTestUtils.invokeMethod(room, "requireReviewedQuorum", 1L, 1, afterVoting);
        }
    }
}
