package com.aimeeting.room.dao;

import com.aimeeting.room.entity.AiWorkflowStageTemplate;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AiWorkflowStageTemplateMapper {
    int insert(AiWorkflowStageTemplate record);
    int batchInsert(List<AiWorkflowStageTemplate> records);
    AiWorkflowStageTemplate selectById(@Param("id") Long id);
    List<AiWorkflowStageTemplate> selectByTemplateId(@Param("templateId") Long templateId);
    int deleteByTemplateId(@Param("templateId") Long templateId);
}
