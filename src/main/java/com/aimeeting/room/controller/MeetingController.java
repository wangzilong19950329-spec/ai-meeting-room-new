package com.aimeeting.room.controller;

import com.aimeeting.room.common.ApiResponse;
import com.aimeeting.room.dto.ContributionResult;
import com.aimeeting.room.dto.MeetingCreateRequest;
import com.aimeeting.room.dto.MeetingDetailVO;
import com.aimeeting.room.entity.AiMeetingScoreLog;
import com.aimeeting.room.service.MeetingRoomService;
import com.aimeeting.room.service.MeetingScoreService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * AI会议室 REST API
 *
 * POST   /api/meetings                        创建会议
 * POST   /api/meetings/{id}/start             启动会议（异步）
 * GET    /api/meetings/{id}                   查询会议详情（含状态轮询）
 * GET    /api/meetings?creatorId=             查询用户的会议列表
 * POST   /api/meetings/{id}/cancel            取消会议
 * POST   /api/meetings/{id}/verify            验证结论有效性
 * GET    /api/meetings/{id}/contributions     查看贡献度详情
 * GET    /api/members/{memberId}/scores       查询成员积分历史
 */
@Slf4j
@RestController
@RequestMapping("/api")
public class MeetingController {

    @Autowired
    private MeetingRoomService meetingRoomService;

    @Autowired
    private MeetingScoreService meetingScoreService;

    /** 创建会议 */
    @PostMapping("/meetings")
    public ApiResponse<Long> createMeeting(@RequestBody @Validated MeetingCreateRequest request) {
        try {
            Long meetingId = meetingRoomService.createMeeting(request);
            return ApiResponse.ok(meetingId);
        } catch (Exception e) {
            log.error("createMeeting error", e);
            return ApiResponse.fail(e.getMessage());
        }
    }

    /** 启动会议（异步执行，立即返回） */
    @PostMapping("/meetings/{id}/start")
    public ApiResponse<Void> startMeeting(@PathVariable Long id) {
        try {
            meetingRoomService.startMeeting(id);
            return ApiResponse.ok();
        } catch (Exception e) {
            log.error("startMeeting error, id={}", id, e);
            return ApiResponse.fail(e.getMessage());
        }
    }

    /** 查询会议详情（可用于轮询进度） */
    @GetMapping("/meetings/{id}")
    public ApiResponse<MeetingDetailVO> getMeetingDetail(@PathVariable Long id) {
        MeetingDetailVO vo = meetingRoomService.getMeetingDetail(id);
        if (vo == null) return ApiResponse.fail(404, "会议不存在");
        return ApiResponse.ok(vo);
    }

    /** 查询用户的会议列表 */
    @GetMapping("/meetings")
    public ApiResponse<List<MeetingDetailVO>> listMeetings(@RequestParam Long creatorId) {
        return ApiResponse.ok(meetingRoomService.listByCreator(creatorId));
    }

    /** 取消会议 */
    @PostMapping("/meetings/{id}/cancel")
    public ApiResponse<Void> cancelMeeting(@PathVariable Long id) {
        try {
            meetingRoomService.cancelMeeting(id);
            return ApiResponse.ok();
        } catch (Exception e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    /** 用户验证结论是否有效，触发积分计算 */
    @PostMapping("/meetings/{id}/verify")
    public ApiResponse<Void> verifyConclusion(@PathVariable Long id,
                                               @RequestParam boolean valid) {
        try {
            meetingRoomService.verifyConclusion(id, valid);
            return ApiResponse.ok();
        } catch (Exception e) {
            log.error("verifyConclusion error, id={}", id, e);
            return ApiResponse.fail(e.getMessage());
        }
    }

    /** 查看本次会议各成员贡献度详情 */
    @GetMapping("/meetings/{id}/contributions")
    public ApiResponse<List<ContributionResult>> getContributions(@PathVariable Long id) {
        return ApiResponse.ok(meetingScoreService.calculateContributions(id));
    }

    /** 查询成员积分历史 */
    @GetMapping("/members/{memberId}/scores")
    public ApiResponse<List<AiMeetingScoreLog>> getMemberScores(@PathVariable Long memberId) {
        return ApiResponse.ok(meetingScoreService.getMemberScoreHistory(memberId));
    }
}
