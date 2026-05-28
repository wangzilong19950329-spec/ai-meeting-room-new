package com.aimeeting.room.dao;

import com.aimeeting.room.entity.AiMemberTemplate;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface AiMemberTemplateMapper {
    List<AiMemberTemplate> selectAll();
    AiMemberTemplate selectById(@Param("id") Long id);
    int insert(AiMemberTemplate template);
    int update(AiMemberTemplate template);
    int deleteById(@Param("id") Long id);
}
