package com.aimeeting.room.controller;

import com.aimeeting.room.common.ApiResponse;
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

    @GetMapping
    public ApiResponse<List<AiMemberTemplate>> list() {
        return ApiResponse.ok(mapper.selectAll());
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
