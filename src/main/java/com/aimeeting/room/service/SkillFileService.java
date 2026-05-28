package com.aimeeting.room.service;

import com.aimeeting.room.dao.AiSkillFileMapper;
import com.aimeeting.room.dto.SkillFileVO;
import com.aimeeting.room.entity.AiSkillFile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class SkillFileService {

    private final AiSkillFileMapper skillFileMapper;

    public List<SkillFileVO> list() {
        List<SkillFileVO> result = new ArrayList<>();
        for (AiSkillFile item : skillFileMapper.selectAll()) {
            result.add(toVO(item));
        }
        return result;
    }

    public SkillFileVO upload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("上传文件不能为空");
        }
        String originalName = sanitizeFileName(file.getOriginalFilename());
        try {
            Path uploadDir = ensureUploadDir();
            String storedName = UUID.randomUUID() + "-" + originalName;
            Path target = uploadDir.resolve(storedName);
            Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);

            AiSkillFile record = new AiSkillFile();
            record.setFileName(originalName);
            record.setStoredName(storedName);
            record.setContentType(StringUtils.hasText(file.getContentType()) ? file.getContentType() : MediaType.APPLICATION_OCTET_STREAM_VALUE);
            record.setFileSize(file.getSize());
            record.setStoragePath(target.toAbsolutePath().toString());
            skillFileMapper.insert(record);
            AiSkillFile saved = skillFileMapper.selectById(record.getId());
            return toVO(saved != null ? saved : record);
        } catch (IOException e) {
            throw new IllegalStateException("保存 skill 文件失败", e);
        }
    }

    public void delete(Long id) {
        AiSkillFile record = skillFileMapper.selectById(id);
        if (record == null) {
            return;
        }
        if (StringUtils.hasText(record.getStoragePath())) {
            try {
                Files.deleteIfExists(Paths.get(record.getStoragePath()));
            } catch (IOException e) {
                log.warn("Failed to delete skill file bytes id={}", id, e);
            }
        }
        skillFileMapper.deleteById(id);
    }

    public ResponseEntity<Resource> download(Long id) {
        AiSkillFile record = skillFileMapper.selectById(id);
        if (record == null || !StringUtils.hasText(record.getStoragePath())) {
            throw new IllegalArgumentException("文件不存在");
        }
        Path path = Paths.get(record.getStoragePath());
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("文件不存在");
        }
        Resource resource = new FileSystemResource(path);
        String encodedName = URLEncoder.encode(record.getFileName(), StandardCharsets.UTF_8).replace("+", "%20");
        ContentDisposition disposition = ContentDisposition.attachment().filename(encodedName, StandardCharsets.UTF_8).build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(MediaType.parseMediaType(StringUtils.hasText(record.getContentType()) ? record.getContentType() : MediaType.APPLICATION_OCTET_STREAM_VALUE))
                .contentLength(record.getFileSize() != null ? record.getFileSize() : path.toFile().length())
                .body(resource);
    }

    public List<Long> normalizeExistingIds(List<Long> ids) {
        List<Long> normalized = new ArrayList<>();
        if (ids == null || ids.isEmpty()) {
            return normalized;
        }
        List<AiSkillFile> existing = skillFileMapper.selectByIds(ids);
        for (AiSkillFile item : existing) {
            normalized.add(item.getId());
        }
        return normalized;
    }

    private SkillFileVO toVO(AiSkillFile item) {
        SkillFileVO vo = new SkillFileVO();
        vo.setId(item.getId());
        vo.setFileName(item.getFileName());
        vo.setContentType(item.getContentType());
        vo.setFileSize(item.getFileSize());
        vo.setGmtCreate(item.getGmtCreate());
        return vo;
    }

    private Path ensureUploadDir() throws IOException {
        Path dir = Paths.get(System.getProperty("user.dir"), "uploads", "skill-files");
        Files.createDirectories(dir);
        return dir;
    }

    private String sanitizeFileName(String input) {
        String name = StringUtils.hasText(input) ? input.trim() : "skill-file";
        name = name.replace("\\", "_").replace("/", "_");
        return name.isEmpty() ? "skill-file" : name;
    }
}
