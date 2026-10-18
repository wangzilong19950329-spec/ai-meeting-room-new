package com.aimeeting.room.controller;

import com.aimeeting.room.common.ApiResponse;
import com.aimeeting.room.dto.ContributionResult;
import com.aimeeting.room.dto.MeetingCreateRequest;
import com.aimeeting.room.dto.MeetingDetailVO;
import com.aimeeting.room.dto.MeetingReviewedTurnRetryRequest;
import com.aimeeting.room.dto.MeetingUsageVO;
import com.aimeeting.room.dto.RoundtableV2ViewVO;
import com.aimeeting.room.dto.SkillFileVO;
import com.aimeeting.room.dto.TrackingItemCreateRequest;
import com.aimeeting.room.dto.TrackingItemUpdateRequest;
import com.aimeeting.room.dto.TrackingOverviewVO;
import com.aimeeting.room.dto.WorkflowStageReviewRequest;
import com.aimeeting.room.dto.WorkflowStageSubmitRequest;
import com.aimeeting.room.dto.WorkflowTemplateRequest;
import com.aimeeting.room.dto.WorkflowTemplateVO;
import com.aimeeting.room.entity.AiMeetingScoreLog;
import com.aimeeting.room.entity.AiWorkflowTemplate;
import com.aimeeting.room.entity.MeetingReviewedTurnSkip;
import com.aimeeting.room.service.MeetingCallLogService;
import com.aimeeting.room.service.MeetingRoomService;
import com.aimeeting.room.service.RoundtableV2Service;
import com.aimeeting.room.service.MeetingScoreService;
import com.aimeeting.room.service.SkillFileService;
import com.aimeeting.room.service.TrackingCenterService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;

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

    @Autowired
    private MeetingCallLogService meetingCallLogService;

    @Autowired
    private RoundtableV2Service roundtableV2Service;

    @Autowired
    private SkillFileService skillFileService;

    @Autowired
    private TrackingCenterService trackingCenterService;

    /** 创建会议 */
    @PostMapping("/meetings")
    public ApiResponse<Long> createMeeting(@RequestBody @Validated MeetingCreateRequest request,
                                            HttpServletRequest httpRequest) {
        try {
            Long currentUserId = (Long) httpRequest.getAttribute("currentUserId");
            if (request.getCreatorId() == null) {
                request.setCreatorId(currentUserId);
            }
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

    /** 准备重试一个确定的本地校验失败；保持暂停，调用方随后单独启动会议。 */
    @PostMapping("/meetings/{id}/reviewed-turns/{memberId}/prepare-retry")
    public ApiResponse<MeetingReviewedTurnSkip> prepareReviewedTurnRetry(@PathVariable Long id,
            @PathVariable Long memberId, @RequestBody @Validated MeetingReviewedTurnRetryRequest request) {
        try {
            return ApiResponse.ok(meetingRoomService.prepareReviewedTurnRetry(id, memberId,
                    request.getSkipId(), request.getExpectedRound(), request.getExpectedEpoch()));
        } catch (Exception e) {
            log.warn("prepareReviewedTurnRetry rejected, meetingId={}, memberId={}: {}", id, memberId, e.getMessage());
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

    /** 本场会议的模型与审核搜索消耗：合计和按环节汇总 */
    @GetMapping("/meetings/{id}/usage")
    public ApiResponse<MeetingUsageVO> getMeetingUsage(@PathVariable Long id) {
        return ApiResponse.ok(meetingCallLogService.usage(id));
    }

    /** 圆桌 v2：问题卡、事实底稿、各议员选择与理由、结论清单与分歧清单 */
    @GetMapping("/meetings/{id}/roundtable-v2")
    public ApiResponse<RoundtableV2ViewVO> getRoundtableV2(@PathVariable Long id) {
        return ApiResponse.ok(roundtableV2Service.view(id));
    }

    /** 圆桌 v2：人为介入（补充资料 FACT / 追加取证 SEARCH / 向议员提问 ASK），排队到下一个环节开始前处理 */
    @PostMapping("/meetings/{id}/roundtable-v2/interventions")
    public ApiResponse<com.alibaba.fastjson.JSONObject> interveneRoundtableV2(@PathVariable Long id,
                                                                            @RequestBody com.alibaba.fastjson.JSONObject request) {
        try {
            return ApiResponse.ok(roundtableV2Service.intervene(id, request));
        } catch (IllegalStateException refused) {
            return ApiResponse.fail(refused.getMessage());
        }
    }

    /** 圆桌 v2：按需生成单人对照（同模型、同问题卡和会前底稿，一次作答），只对已结束的会议有效，只存一份 */
    @PostMapping("/meetings/{id}/roundtable-v2/solo")
    public ApiResponse<com.alibaba.fastjson.JSONObject> createRoundtableV2Solo(@PathVariable Long id) {
        try {
            return ApiResponse.ok(roundtableV2Service.solo(id));
        } catch (IllegalStateException refused) {
            return ApiResponse.fail(refused.getMessage());
        }
    }

    /** 查询用户的会议列表 */
    @GetMapping("/meetings")
    public ApiResponse<List<MeetingDetailVO>> listMeetings(@RequestParam Long creatorId) {
        return ApiResponse.ok(meetingRoomService.listByCreator(creatorId));
    }

    /** 查询可用会议流程模板 */
    @GetMapping("/workflow-templates")
    public ApiResponse<List<AiWorkflowTemplate>> listWorkflowTemplates() {
        return ApiResponse.ok(meetingRoomService.listWorkflowTemplates());
    }

    /** 查询流程模板详情（含阶段定义） */
    @GetMapping("/workflow-templates/detail")
    public ApiResponse<List<WorkflowTemplateVO>> listWorkflowTemplateDetails() {
        return ApiResponse.ok(meetingRoomService.listWorkflowTemplateDetails());
    }

    /** 创建自定义流程模板 */
    @PostMapping("/workflow-templates")
    public ApiResponse<WorkflowTemplateVO> createWorkflowTemplate(@RequestBody @Validated WorkflowTemplateRequest request) {
        try {
            return ApiResponse.ok(meetingRoomService.createWorkflowTemplate(request));
        } catch (Exception e) {
            log.error("createWorkflowTemplate error", e);
            return ApiResponse.fail(e.getMessage());
        }
    }

    /** 更新自定义流程模板 */
    @PutMapping("/workflow-templates/{id}")
    public ApiResponse<WorkflowTemplateVO> updateWorkflowTemplate(@PathVariable Long id,
                                                                  @RequestBody @Validated WorkflowTemplateRequest request) {
        try {
            return ApiResponse.ok(meetingRoomService.updateWorkflowTemplate(id, request));
        } catch (Exception e) {
            log.error("updateWorkflowTemplate error, id={}", id, e);
            return ApiResponse.fail(e.getMessage());
        }
    }

    /** 删除自定义流程模板 */
    @DeleteMapping("/workflow-templates/{id}")
    public ApiResponse<Void> deleteWorkflowTemplate(@PathVariable Long id) {
        try {
            meetingRoomService.deleteWorkflowTemplate(id);
            return ApiResponse.ok();
        } catch (Exception e) {
            log.error("deleteWorkflowTemplate error, id={}", id, e);
            return ApiResponse.fail(e.getMessage());
        }
    }

    @GetMapping("/skill-files")
    public ApiResponse<List<SkillFileVO>> listSkillFiles() {
        return ApiResponse.ok(skillFileService.list());
    }

    @PostMapping("/skill-files/upload")
    public ApiResponse<SkillFileVO> uploadSkillFile(@RequestParam("file") MultipartFile file) {
        try {
            return ApiResponse.ok(skillFileService.upload(file));
        } catch (Exception e) {
            log.error("uploadSkillFile error", e);
            return ApiResponse.fail(e.getMessage());
        }
    }

    @DeleteMapping("/skill-files/{id}")
    public ApiResponse<Void> deleteSkillFile(@PathVariable Long id) {
        try {
            skillFileService.delete(id);
            return ApiResponse.ok();
        } catch (Exception e) {
            log.error("deleteSkillFile error, id={}", id, e);
            return ApiResponse.fail(e.getMessage());
        }
    }

    @GetMapping("/skill-files/{id}/download")
    public ResponseEntity<Resource> downloadSkillFile(@PathVariable Long id) {
        return skillFileService.download(id);
    }

    /** 暂停会议 */
    @PostMapping({"/meetings/{id}/cancel", "/meetings/{id}/pause"})
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

    /** 复制会议（复用成员配置，清空讨论记录，可修改主题） */
    @PostMapping("/meetings/{id}/clone")
    public ApiResponse<Long> cloneMeeting(@PathVariable Long id,
                                           @RequestBody(required = false) Map<String, String> body,
                                           HttpServletRequest httpRequest) {
        try {
            Long currentUserId = (Long) httpRequest.getAttribute("currentUserId");
            String newTitle = body != null ? body.get("title") : null;
            Long newId = meetingRoomService.cloneMeeting(id, newTitle, currentUserId);
            return ApiResponse.ok(newId);
        } catch (Exception e) {
            log.error("cloneMeeting error, id={}", id, e);
            return ApiResponse.fail(e.getMessage());
        }
    }

    /** 强制终止并生成结论（进行中的会议） */
    @PostMapping("/meetings/{id}/terminate")
    public ApiResponse<Void> terminateMeeting(@PathVariable Long id) {
        try {
            meetingRoomService.terminateMeeting(id);
            return ApiResponse.ok();
        } catch (Exception e) {
            log.error("terminateMeeting error, id={}", id, e);
            return ApiResponse.fail(e.getMessage());
        }
    }

    /** 重启会议（清空记录，重置为待启动状态） */
    @PostMapping("/meetings/{id}/restart")
    public ApiResponse<Void> restartMeeting(@PathVariable Long id) {
        try {
            meetingRoomService.restartMeeting(id);
            return ApiResponse.ok();
        } catch (Exception e) {
            log.error("restartMeeting error, id={}", id, e);
            return ApiResponse.fail(e.getMessage());
        }
    }

    /** 删除会议及其全部关联数据（成员/发言/投票/共识/积分/工作流/追踪项，级联清理） */
    @DeleteMapping("/meetings/{id}")
    public ApiResponse<Void> deleteMeeting(@PathVariable Long id) {
        try {
            meetingRoomService.deleteMeeting(id);
            return ApiResponse.ok();
        } catch (Exception e) {
            log.error("deleteMeeting error, id={}", id, e);
            return ApiResponse.fail(e.getMessage());
        }
    }

    /** 批量删除会议及其全部关联数据（复用单会议级联清理与状态保护） */
    @PostMapping("/meetings/batch-delete")
    public ApiResponse<Integer> deleteMeetings(@RequestBody Map<String, List<Long>> body) {
        try {
            List<Long> ids = body == null ? null : body.get("ids");
            int deleted = meetingRoomService.deleteMeetings(ids);
            return ApiResponse.ok(deleted);
        } catch (Exception e) {
            log.error("deleteMeetings error", e);
            return ApiResponse.fail(e.getMessage());
        }
    }

    /** 人类用户向会议发送消息 */
    @PostMapping("/meetings/{id}/human-message")
    public ApiResponse<Void> sendHumanMessage(@PathVariable Long id,
                                               @RequestBody Map<String, String> body) {
        try {
            String content = body.get("content");
            if (content == null || content.trim().isEmpty()) return ApiResponse.fail("消息不能为空");
            boolean asDirective = "directive".equalsIgnoreCase(body.get("mode"));
            Long targetMemberId = null;
            if (body.get("targetMemberId") != null && !body.get("targetMemberId").trim().isEmpty()) {
                targetMemberId = Long.valueOf(body.get("targetMemberId").trim());
            }
            meetingRoomService.addHumanMessage(id, content.trim(), asDirective, targetMemberId);
            return ApiResponse.ok();
        } catch (Exception e) {
            log.error("sendHumanMessage error, id={}", id, e);
            return ApiResponse.fail(e.getMessage());
        }
    }

    /** 更新追踪项状态/备注 */
    @PostMapping("/tracking-items/{id}/status")
    public ApiResponse<Void> updateTrackingItem(@PathVariable Long id,
                                                @RequestBody @Validated TrackingItemUpdateRequest request) {
        try {
            meetingRoomService.updateTrackingItem(id, request);
            return ApiResponse.ok();
        } catch (Exception e) {
            log.error("updateTrackingItem error, id={}", id, e);
            return ApiResponse.fail(e.getMessage());
        }
    }

    @GetMapping("/tracking-items/overview")
    public ApiResponse<TrackingOverviewVO> trackingOverview() {
        return ApiResponse.ok(trackingCenterService.overview());
    }

    @PostMapping("/tracking-items/custom")
    public ApiResponse<TrackingOverviewVO.TrackingItemVO> createCustomTrackingItem(
            @RequestBody @Validated TrackingItemCreateRequest request) {
        try {
            return ApiResponse.ok(trackingCenterService.createCustomItem(request));
        } catch (Exception e) {
            log.error("createCustomTrackingItem error", e);
            return ApiResponse.fail(e.getMessage());
        }
    }

    @PostMapping("/tracking-items/{id}/refresh")
    public ApiResponse<TrackingOverviewVO.TrackingItemVO> refreshTrackingItem(@PathVariable Long id) {
        try {
            return ApiResponse.ok(trackingCenterService.refreshItemNow(id));
        } catch (Exception e) {
            log.error("refreshTrackingItem error, id={}", id, e);
            return ApiResponse.fail(e.getMessage());
        }
    }

    @PostMapping("/tracking-items/{id}/reopen-meeting")
    public ApiResponse<Void> reopenMeetingFromTracking(@PathVariable Long id) {
        try {
            trackingCenterService.reopenMeeting(id);
            return ApiResponse.ok();
        } catch (Exception e) {
            log.error("reopenMeetingFromTracking error, id={}", id, e);
            return ApiResponse.fail(e.getMessage());
        }
    }

    /** 提交当前工作流阶段内容 */
    @PostMapping("/meetings/{id}/workflow/submit")
    public ApiResponse<Void> submitWorkflowStage(@PathVariable Long id,
                                                 @RequestBody @Validated WorkflowStageSubmitRequest request) {
        try {
            meetingRoomService.submitWorkflowStage(id, request);
            return ApiResponse.ok();
        } catch (Exception e) {
            log.error("submitWorkflowStage error, meetingId={}", id, e);
            return ApiResponse.fail(e.getMessage());
        }
    }

    /** 审核当前工作流阶段 */
    @PostMapping("/meetings/{id}/workflow/review")
    public ApiResponse<Void> reviewWorkflowStage(@PathVariable Long id,
                                                 @RequestBody @Validated WorkflowStageReviewRequest request) {
        try {
            meetingRoomService.reviewWorkflowStage(id, request);
            return ApiResponse.ok();
        } catch (Exception e) {
            log.error("reviewWorkflowStage error, meetingId={}", id, e);
            return ApiResponse.fail(e.getMessage());
        }
    }

    /** 重试当前自动执行节点 */
    @PostMapping("/meetings/{id}/workflow/retry-auto")
    public ApiResponse<Void> retryWorkflowAutoStage(@PathVariable Long id) {
        try {
            meetingRoomService.retryWorkflowAutoStage(id);
            return ApiResponse.ok();
        } catch (Exception e) {
            log.error("retryWorkflowAutoStage error, id={}", id, e);
            return ApiResponse.fail(e.getMessage());
        }
    }
}
