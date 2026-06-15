package com.aimeeting.room.controller;

import com.aimeeting.room.common.ApiResponse;
import com.aimeeting.room.dao.AiMeetingMemberMapper;
import com.aimeeting.room.dao.AiMemberTemplateMapper;
import com.aimeeting.room.entity.AiMemberTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/member-templates")
public class MemberTemplateController {

    @Autowired
    private AiMemberTemplateMapper mapper;

    @Autowired
    private AiMeetingMemberMapper meetingMemberMapper;

    @GetMapping
    public ApiResponse<List<AiMemberTemplate>> list() {
        List<AiMemberTemplate> templates = mapper.selectAll();
        // 填充占用数：以该模版名称参会的议员数量（议员是创建会议时复制的，按名称近似统计）
        for (AiMemberTemplate t : templates) {
            t.setUsageCount(t.getName() != null ? meetingMemberMapper.countByMemberName(t.getName()) : 0);
        }
        return ApiResponse.ok(templates);
    }

    @PostMapping
    public ApiResponse<Long> create(@RequestBody AiMemberTemplate template) {
        if (template.getName() == null || template.getName().trim().isEmpty()) {
            return ApiResponse.fail("模版名称不能为空");
        }
        mapper.insert(template);
        return ApiResponse.ok(template.getId());
    }

    @PutMapping("/{id}")
    public ApiResponse<Void> update(@PathVariable Long id, @RequestBody AiMemberTemplate template) {
        template.setId(id);
        mapper.update(template);
        return ApiResponse.ok();
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        mapper.deleteById(id);
        return ApiResponse.ok();
    }
}
