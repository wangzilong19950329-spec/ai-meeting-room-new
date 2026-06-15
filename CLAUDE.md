# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

多模型 AI 圆桌会议系统：多个 AI 议员（各自绑定不同 AI 模型）围绕议题轮流辩论、投票并形成共识。

## Commands

### Backend (Spring Boot, port 8080 locally)
```bash
mvn spring-boot:run                            # 本地启动
mvn package -DskipTests                        # 打包（跳过测试）
mvn test                                       # 运行所有测试
mvn test -Dtest=ClassName#methodName           # 运行单个测试方法
```

### Frontend (Vue 3 + Vite, port 3000 locally)
```bash
cd frontend
npm install
npm run dev       # 本地开发（代理 /api → 47.111.227.125:8081）
npm run build     # 构建到 frontend/dist/
```

### Server Deployment
```bash
# 前端：本地改动 → SFTP 上传到服务器 /opt/ai-meeting-room/frontend/src/ → 服务器执行：
cd /opt/ai-meeting-room/frontend && npm run build

# 后端：服务器执行：
cd /opt/ai-meeting-room && mvn package -DskipTests
kill $(pgrep -f ai-meeting-room) && nohup java -jar target/ai-meeting-room-1.0.0-SNAPSHOT.jar &
```

## Architecture

### Meeting Execution Flow

1. `MeetingController` — REST 入口，`POST /api/meetings/{id}/start` 触发异步会议
2. `MeetingRoomService` — 顶层编排：初始化→多轮辩论→投票→聚类共识→评分→通知
3. `MeetingDebateService` — 每轮辩论：对每个议员执行两阶段发言生成
   - 阶段1：调用模型判断是否需要网络搜索（`WebSearchService` Serper API）
   - 阶段2：注入搜索结果后生成正式发言，响应格式为 `{JSON证据}==REPLY==发言内容`
4. `MeetingOrchestratorService` — 构建所有提示词（上下文窗口：最近2轮完整 + 早期轮次压缩至80字）
5. `MeetingConsensusService` — 投票后对立场做语义聚类，判断是否达成共识（threshold 70%）
6. `MeetingScoreService` — 按贡献度（共识对齐）计算积分奖惩

### AI Model Layer

- `AiModelClient` — 接口，每个实现类对应一种协议
- `GenericAiClient` (`@Order(0)`) — **主要客户端**：从 `ModelRegistryStore` 查配置，支持 OpenAI 兼容格式和 Claude 格式；DeepSeek Chat 会触发 `$web_search` tool call
- `ClaudeClient` / `OpenAiClient` / `WenxinClient` — 旧实现，`@Order(1+)`，仅当 `GenericAiClient` 的 `supports()` 返回 false 时兜底
- `AiModelRouter` — 遍历所有 `AiModelClient` bean，找到第一个 `supports(modelType)` 为 true 的执行

### Model Configuration

- `ModelRegistryStore` — **运行时内存**注册表，`@PostConstruct` 从 `application.yml` 预填充内置模型；通过 `ConfigController` 可在运行时增删改，**重启后恢复**
- `ModelEntry.apiFormat` — `"openai"` 或 `"claude"`，决定 `GenericAiClient` 使用哪种请求格式
- 通义千问通过 `apiFormat=openai` 接入（DashScope 兼容 OpenAI 接口）

### Auth

- `TokenStore` — 内存 token 存储（Map），无 JWT，无过期机制
- `AuthInterceptor` — 读取请求头 `X-Token` 或参数 `token`，注入 `currentUserId` 到 `HttpServletRequest.attribute`
- 公开路径配置在 `WebMvcConfig`（登录接口不拦截）

### Frontend

- 所有 HTTP 请求走 `/api/` 前缀，本地开发由 Vite proxy 转发，生产环境由 Nginx 反代到后端 8080
- `frontend/src/api/config.js` — 使用 `X-Token` header 的 axios 实例；`auth.js` 负责登录/注册
- Pinia store (`stores/user.js`) 持久化 token 和用户信息到 localStorage
- `MeetingDetail.vue` — 核心页面，轮询 `GET /api/meetings/{id}` 展示实时会议进展

## Key Notes

- **数据库**：MySQL 仅允许服务器 localhost 连接，本地无法直连，需 SSH 操作
- **模型配置持久化**：`ModelRegistryStore` 重启后丢失运行时修改；生产环境如需持久化须接入数据库
- **DeepSeek Reasoner**：`deepseek-reasoner` 不支持 tool call，阶段1搜索决策改用 `deepseek-chat` 代为执行
- **会议异步执行**：`meetingExecutor`（线程池大小 `ai.meeting.async-thread-pool-size`，默认5），启动后立即返回，前端轮询状态

## Database Tables

| 表名 | 说明 |
|------|------|
| ai_user | 用户表 |
| ai_meeting_room | 会议主表（含状态、当前轮次、最终结论） |
| ai_meeting_member | 会议议员（绑定 modelType 和 persona） |
| ai_meeting_opinion | 发言记录（含 evidence JSON、opinionType） |
| ai_meeting_vote | 投票记录（含 position 和 confidence） |
| ai_meeting_conclusion_cluster | 共识聚类结果 |
| ai_meeting_score_log | 积分变动日志 |
| ai_member_template | 议员模版库 |

## Server Info

- **公网 IP**: 47.111.227.125
- **项目路径**: /opt/ai-meeting-room/
- **Nginx 配置**: /etc/nginx/conf.d/ai-meeting.conf
