package com.aimeeting.room.service;

import com.aimeeting.room.dao.AiMeetingRoomMapper;
import com.aimeeting.room.entity.AiMeetingRoom;
import com.aimeeting.room.enums.MeetingStatusEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Meetings run on in-memory workers, so after a restart nothing is running a meeting that is still marked running
 * or reopening: it would show as running forever and could not be continued. On startup each such meeting is
 * paused with a new epoch (a stale worker can no longer write) and the reason, so "continue" resumes it from its
 * checkpoints. Switch it off for a second instance that shares the database with one that is running meetings.
 */
@Slf4j
@Component
public class InterruptedMeetingRecovery {
    public static final String REASON = "服务重启时会议中断，已自动暂停；点「继续」从中断处接着跑";

    private final AiMeetingRoomMapper rooms;
    private final MeetingWorkflowService workflow;
    private final boolean enabled;

    public InterruptedMeetingRecovery(AiMeetingRoomMapper rooms, MeetingWorkflowService workflow,
                                      @Value("${ai.meeting.pause-interrupted-on-startup:true}") boolean enabled) {
        this.rooms = rooms;
        this.workflow = workflow;
        this.enabled = enabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (enabled) pauseInterrupted();
    }

    public void pauseInterrupted() {
        List<AiMeetingRoom> interrupted = rooms.selectByStatuses(List.of(
                MeetingStatusEnum.IN_PROGRESS.getCode(), MeetingStatusEnum.REOPENED.getCode()));
        for (AiMeetingRoom room : interrupted) {
            long epoch = room.getRunEpoch() == null ? 0L : room.getRunEpoch();
            try {
                if (rooms.updateStatusIfCurrentAndEpoch(room.getId(), room.getStatus(), epoch,
                        MeetingStatusEnum.PAUSED.getCode()) == 1) {
                    workflow.markReviewedEvidenceBlocked(room.getId(), REASON);
                    log.info("Meeting {} was interrupted by the restart and is now paused", room.getId());
                }
            } catch (RuntimeException failure) {
                log.warn("Interrupted meeting {} could not be paused: {}", room.getId(), failure.getMessage());
            }
        }
    }
}
