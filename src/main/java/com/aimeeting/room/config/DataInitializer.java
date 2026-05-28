package com.aimeeting.room.config;

import com.aimeeting.room.dao.AiUserMapper;
import com.aimeeting.room.entity.AiUser;
import com.aimeeting.room.service.MeetingWorkflowService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 应用启动时初始化默认管理员账号（密码写入正确的 BCrypt hash）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements ApplicationRunner {

    private final AiUserMapper userMapper;
    private final BCryptPasswordEncoder passwordEncoder;
    private final MeetingWorkflowService meetingWorkflowService;

    @Override
    public void run(ApplicationArguments args) {
        meetingWorkflowService.ensureDefaultTemplateExists();

        String defaultPassword = "admin123";
        String encodedPassword = passwordEncoder.encode(defaultPassword);

        AiUser admin = userMapper.selectByUsername("admin");
        if (admin == null) {
            AiUser user = new AiUser();
            user.setUsername("admin");
            user.setPassword(encodedPassword);
            user.setNickname("管理员");
            userMapper.insert(user);
            log.info("Default admin account created");
        } else {
            // 校验现有密码是否正确，不正确则重置
            if (!passwordEncoder.matches(defaultPassword, admin.getPassword())) {
                userMapper.updatePassword(admin.getId(), encodedPassword);
                log.info("Admin password reset to default");
            } else {
                log.info("Admin account verified OK");
            }
        }
    }
}
