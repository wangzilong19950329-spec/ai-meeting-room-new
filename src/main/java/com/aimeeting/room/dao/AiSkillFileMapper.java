package com.aimeeting.room.dao;

import com.aimeeting.room.entity.AiSkillFile;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AiSkillFileMapper {
    int insert(AiSkillFile record);
    AiSkillFile selectById(@Param("id") Long id);
    List<AiSkillFile> selectAll();
    List<AiSkillFile> selectByIds(@Param("ids") List<Long> ids);
    int deleteById(@Param("id") Long id);
}
