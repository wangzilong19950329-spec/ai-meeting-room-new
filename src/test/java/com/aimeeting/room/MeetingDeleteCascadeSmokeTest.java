package com.aimeeting.room;

import com.aimeeting.room.dao.AiMeetingMemberMapper;
import com.aimeeting.room.dao.AiMeetingRoomMapper;
import com.aimeeting.room.enums.MeetingStatusEnum;
import com.aimeeting.room.service.MeetingRoomService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 删除会议级联清理 + 议员模版占用计数 的冒烟测试。
 * 跑在 H2 内存库（MySQL 兼容模式）+ schema-local.sql 之上，验证：
 *  1. deleteMeeting 把会议涉及的全部子表数据一并清除；
 *  2. 进行中/修订中的会议删除时会先停止，再执行同一套级联清理；
 *  3. countByMemberName 正确反映模版被会议引用的次数。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("smoketest")
class MeetingDeleteCascadeSmokeTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private MeetingRoomService meetingRoomService;
    @Autowired private AiMeetingRoomMapper roomMapper;
    @Autowired private AiMeetingMemberMapper memberMapper;

    /** 在所有会议子表中各插入一行（含工作流/追踪/积分），返回新会议 id。memberName 用于占用计数。 */
    private long seedFullMeeting(int status, String memberName) {
        jdbc.update("INSERT INTO ai_meeting_room (title, creator_id, status, max_rounds, current_round) VALUES (?,?,?,?,?)",
                "冒烟会议", 999L, status, 5, 1);
        Long meetingId = jdbc.queryForObject("SELECT MAX(id) FROM ai_meeting_room", Long.class);

        // 两个同名议员 —— 用于占用计数断言
        jdbc.update("INSERT INTO ai_meeting_member (meeting_id, member_name, model_type, persona) VALUES (?,?,?,?)",
                meetingId, memberName, "deepseek-chat", "立场背景");
        jdbc.update("INSERT INTO ai_meeting_member (meeting_id, member_name, model_type, persona) VALUES (?,?,?,?)",
                meetingId, memberName, "deepseek-chat", "立场背景");

        jdbc.update("INSERT INTO ai_meeting_opinion (meeting_id, member_id, round_number, content, opinion_type) VALUES (?,?,?,?,?)",
                meetingId, 1L, 1, "一段发言", 0);
        jdbc.update("INSERT INTO ai_meeting_vote (meeting_id, member_id, round_number, vote_position, confidence) VALUES (?,?,?,?,?)",
                meetingId, 1L, 1, "赞成", 0.80);
        jdbc.update("INSERT INTO ai_meeting_conclusion_cluster (meeting_id, round_number, cluster_label, member_count, member_ids, agreement_ratio, is_consensus) VALUES (?,?,?,?,?,?,?)",
                meetingId, 1, "聚类标签", 1, "1", 0.50, 0);
        jdbc.update("INSERT INTO ai_meeting_score_log (meeting_id, member_id, score_delta, reason) VALUES (?,?,?,?)",
                meetingId, 1L, 10, "贡献奖励");

        jdbc.update("INSERT INTO ai_workflow_run (meeting_id, template_id, template_key, current_stage_key, current_stage_order, status) VALUES (?,?,?,?,?,?)",
                meetingId, 1L, "default", "stage1", 1, "RUNNING");
        Long runId = jdbc.queryForObject("SELECT MAX(id) FROM ai_workflow_run", Long.class);
        jdbc.update("INSERT INTO ai_workflow_stage_run (workflow_run_id, meeting_id, stage_template_id, stage_key, stage_name, stage_order, stage_type, actor_type, status) VALUES (?,?,?,?,?,?,?,?,?)",
                runId, meetingId, 1L, "stage1", "阶段一", 1, "AUTO", "AI", "RUNNING");
        Long stageRunId = jdbc.queryForObject("SELECT MAX(id) FROM ai_workflow_stage_run", Long.class);
        jdbc.update("INSERT INTO ai_workflow_stage_submission (workflow_run_id, stage_run_id, meeting_id, stage_key, action_type) VALUES (?,?,?,?,?)",
                runId, stageRunId, meetingId, "stage1", "SUBMIT");

        jdbc.update("INSERT INTO ai_meeting_tracking_item (meeting_id, workflow_run_id, stage_run_id, source_type, item_type, title, status) VALUES (?,?,?,?,?,?,?)",
                meetingId, runId, stageRunId, "MEETING", "TASK", "追踪项标题", "OPEN");
        Long itemId = jdbc.queryForObject("SELECT MAX(id) FROM ai_meeting_tracking_item", Long.class);
        jdbc.update("INSERT INTO ai_meeting_tracking_update (tracking_item_id, meeting_id, update_type) VALUES (?,?,?)",
                itemId, meetingId, "STATUS_CHANGE");

        return meetingId;
    }

    private int countFor(String table, long meetingId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE meeting_id = ?", Integer.class, meetingId);
    }

    private void assertMeetingRowsRemoved(long meetingId) {
        assertEquals(0, countFor("ai_meeting_member", meetingId));
        assertEquals(0, countFor("ai_meeting_opinion", meetingId));
        assertEquals(0, countFor("ai_meeting_vote", meetingId));
        assertEquals(0, countFor("ai_meeting_conclusion_cluster", meetingId));
        assertEquals(0, countFor("ai_meeting_score_log", meetingId));
        assertEquals(0, countFor("ai_workflow_run", meetingId));
        assertEquals(0, countFor("ai_workflow_stage_run", meetingId));
        assertEquals(0, countFor("ai_workflow_stage_submission", meetingId));
        assertEquals(0, countFor("ai_meeting_tracking_item", meetingId));
        assertEquals(0, countFor("ai_meeting_tracking_update", meetingId));
        assertNull(roomMapper.selectById(meetingId));
    }

    @Test
    void cascadeDeleteRemovesAllChildRows() {
        long meetingId = seedFullMeeting(MeetingStatusEnum.CONCLUDED.getCode(), "议员-级联");

        // 删除前：每张子表都有数据
        assertEquals(2, countFor("ai_meeting_member", meetingId));
        assertEquals(1, countFor("ai_meeting_opinion", meetingId));
        assertEquals(1, countFor("ai_meeting_vote", meetingId));
        assertEquals(1, countFor("ai_meeting_conclusion_cluster", meetingId));
        assertEquals(1, countFor("ai_meeting_score_log", meetingId));
        assertEquals(1, countFor("ai_workflow_run", meetingId));
        assertEquals(1, countFor("ai_workflow_stage_run", meetingId));
        assertEquals(1, countFor("ai_workflow_stage_submission", meetingId));
        assertEquals(1, countFor("ai_meeting_tracking_item", meetingId));
        assertEquals(1, countFor("ai_meeting_tracking_update", meetingId));
        assertNotNull(roomMapper.selectById(meetingId));

        meetingRoomService.deleteMeeting(meetingId);

        // 删除后：全部清零，主表也没了
        assertMeetingRowsRemoved(meetingId);
    }

    @Test
    void activeMeetingCanBeStoppedAndCascadeDeleted() {
        int[] activeStatuses = {
                MeetingStatusEnum.IN_PROGRESS.getCode(),
                MeetingStatusEnum.REOPENED.getCode()
        };
        for (int status : activeStatuses) {
            long meetingId = seedFullMeeting(status, "议员-运行中-" + status);

            meetingRoomService.deleteMeeting(meetingId);

            assertMeetingRowsRemoved(meetingId);
        }
    }

    @Test
    void countByMemberNameReflectsUsage() {
        seedFullMeeting(MeetingStatusEnum.CONCLUDED.getCode(), "议员-计数");

        assertEquals(2, memberMapper.countByMemberName("议员-计数"));
        assertEquals(0, memberMapper.countByMemberName("从未出现过的议员名"));
    }
}
