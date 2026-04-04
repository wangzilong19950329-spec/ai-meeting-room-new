package com.aimeeting.room.dao;

import com.aimeeting.room.entity.AiUser;
import org.apache.ibatis.annotations.*;

@Mapper
public interface AiUserMapper {

    @Select("SELECT * FROM ai_user WHERE username = #{username}")
    AiUser selectByUsername(String username);

    @Insert("INSERT INTO ai_user(username, password, nickname) VALUES(#{username}, #{password}, #{nickname})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(AiUser user);

    @Select("SELECT * FROM ai_user WHERE id = #{id}")
    AiUser selectById(Long id);

    @Update("UPDATE ai_user SET password = #{password} WHERE id = #{id}")
    int updatePassword(@Param("id") Long id, @Param("password") String password);
}
