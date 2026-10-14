package com.aimeeting.room;

import com.aimeeting.room.dao.AiMeetingOpinionMapper;
import com.aimeeting.room.dao.AiMeetingRoomMapper;
import com.aimeeting.room.entity.AiMeetingOpinion;
import com.aimeeting.room.entity.AiMeetingRoom;
import com.aimeeting.room.enums.MeetingStatusEnum;
import com.aimeeting.room.service.MeetingRoomService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "AI_MEETING_REVIEWED_SEARCH_ENABLED=true")
@ActiveProfiles("smoketest")
class MeetingRoomRestartRaceTest {
    @Autowired private AiMeetingRoomMapper rooms;
    @Autowired private AiMeetingOpinionMapper opinions;
    @Autowired private MeetingRoomService roomService;
    @Autowired private PlatformTransactionManager transactions;

    @Test
    void restartWaitsForRoomLockThenRejectsAConcurrentStartWithoutDeletingOpinions() throws Exception {
        AiMeetingRoom room = new AiMeetingRoom();
        room.setTitle("restart race");
        room.setCreatorId(1L);
        room.setStatus(MeetingStatusEnum.PAUSED.getCode());
        room.setMaxRounds(1);
        room.setCurrentRound(1);
        rooms.insert(room);

        AiMeetingOpinion opinion = new AiMeetingOpinion();
        opinion.setMeetingId(room.getId());
        opinion.setMemberId(0L);
        opinion.setRoundNumber(1);
        opinion.setContent("preserve after rejected restart");
        opinion.setOpinionType(3);
        opinions.insert(opinion);

        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch restartEntered = new CountDownLatch(1);
        ExecutorService callers = Executors.newFixedThreadPool(2);
        try {
            Future<?> starter = callers.submit(() -> {
                new TransactionTemplate(transactions).execute(status -> {
                    rooms.selectByIdForUpdate(room.getId());
                    locked.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("test lock release timeout");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("test interrupted", e);
                    }
                    assertEquals(1, rooms.claimReviewedRun(room.getId(),
                            MeetingStatusEnum.PAUSED.getCode(), 0L,
                            MeetingStatusEnum.IN_PROGRESS.getCode()));
                    return null;
                });
            });
            assertTrue(locked.await(5, TimeUnit.SECONDS));

            Future<Boolean> restart = callers.submit(() -> {
                restartEntered.countDown();
                try {
                    roomService.restartMeeting(room.getId());
                    return true;
                } catch (IllegalStateException expected) {
                    return false;
                }
            });
            assertTrue(restartEntered.await(5, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> restart.get(100, TimeUnit.MILLISECONDS));
            release.countDown();
            starter.get(5, TimeUnit.SECONDS);
            assertFalse(restart.get(5, TimeUnit.SECONDS));
            assertEquals(MeetingStatusEnum.IN_PROGRESS.getCode(), rooms.selectById(room.getId()).getStatus());
            assertEquals(1L, rooms.selectById(room.getId()).getRunEpoch());
            assertEquals(1, opinions.selectByMeetingId(room.getId()).size());
        } finally {
            release.countDown();
            callers.shutdownNow();
            opinions.deleteByMeetingId(room.getId());
            rooms.deleteById(room.getId());
        }
    }
}
