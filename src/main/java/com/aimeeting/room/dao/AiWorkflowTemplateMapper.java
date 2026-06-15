package com.aimeeting.room.dao;

import com.aimeeting.room.entity.AiWorkflowTemplate;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AiWorkflowTemplateMapper {
    int insert(AiWorkflowTemplate record);
    AiWorkflowTemplate selectById(@Param("id") Long id);
    AiWorkflowTemplate selectByKey(@Param("templateKey") String templateKey);
    List<AiWorkflowTemplate> selectActiveTemplates();
    List<AiWorkflowTemplate> selectAll();
    int update(AiWorkflowTemplate record);
    int deleteById(@Param("id") Long id);
}
