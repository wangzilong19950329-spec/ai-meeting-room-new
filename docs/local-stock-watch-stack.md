# 本地股票盯盘一键启动

股票盯盘的 DeepSeek 网页分析链路不是单进程：

```text
frontend 3009 -> backend 8127 -> stock DeepSeek bridge 8791 -> Chrome debug 9344 -> DeepSeek 网页
```

股票盯盘现在默认使用独立 DeepSeek 网页环境，和会议/人工分析的通用 bridge
隔离。推荐分工：

- 主号：会议、人工临时分析、通用 `ai.search.deepseek-web`。
- 第二个号：股票盯盘技术分析、股票 AI 异动规则 fallback。

两个账号不要自动轮询互相顶替；如果任一账号出现禁言，应停用该账号对应
bridge，而不是切另一个账号继续批量补打。

## 一键启动

```bash
scripts/start-stock-watch-stack.sh
```

默认端口：

- frontend: `http://127.0.0.1:3009/stock-watch`
- backend: `http://127.0.0.1:8127`
- stock DeepSeek bridge: `http://127.0.0.1:8791/health`
- stock Chrome debug: `http://127.0.0.1:9344/json/version`

日志默认写到：

```text
~/.aimeeting-room/logs/
```

stock 专用账号的本地文件默认在：

```text
~/.aimeeting-room/deepseek-web-stock-profile/
~/.aimeeting-room/deepseek-web-stock-sessions.json
~/.aimeeting-room/deepseek-web-stock-chrome.log
```

首次使用时，脚本会打开一个独立 Chrome profile。请在这个窗口里手动登录
第二个 DeepSeek 账号；不要把密码写入配置文件或聊天记录。脚本会预创建空的
`Default` 目录，避免复制主 Chrome profile 的登录态。

可覆盖的环境变量：

- `AI_MEETING_BACKEND_PORT`
- `AI_MEETING_FRONTEND_PORT`
- `STOCK_WATCH_DEEPSEEK_WEB_BRIDGE_PORT`
- `STOCK_WATCH_DEEPSEEK_WEB_DEBUG_PORT`
- `STOCK_WATCH_DEEPSEEK_WEB_PROFILE_ROOT`
- `STOCK_WATCH_DEEPSEEK_WEB_SESSION_STORE`
- `STOCK_WATCH_DEEPSEEK_WEB_CHROME_LOG`
- `AI_MEETING_ENV_FILE`
- `AI_MEETING_LOG_DIR`

`start-stock-watch-stack.sh` 会把后端盯盘真实读取的变量导出为：

```text
STOCK_WATCH_TECH_BRIDGE_URL=http://127.0.0.1:<stock-bridge-port>/search
STOCK_WATCH_TECH_DEBUG_PORT=<stock-debug-port>
STOCK_WATCH_AI_RULES_BRIDGE_URL=http://127.0.0.1:<stock-bridge-port>/search
```

会议相关的 `ai.search.deepseek-web.*` 和
`ai.meeting.deepseek-web-bridge.*` 不会被这个脚本改到 stock bridge。

## 基础冒烟

默认冒烟只检查本地 bridge 健康，不向 DeepSeek 发送真实 query：

```bash
curl -fsS http://127.0.0.1:8791/health
```

确认返回中的 `debugPort` 是 `9344`。只有确认第二个账号已在 stock profile
中登录，并且你明确要做模型调用验证时，才运行真实 query 冒烟：

```bash
STOCK_WATCH_AI_RULES_BRIDGE_URL=http://127.0.0.1:8791/search \
  node scripts/stock-watch-ai-rule-expert-smoke.js
```

## 停止由脚本启动的进程

```bash
scripts/stop-stock-watch-stack.sh
```

脚本默认只会停止 `start-stock-watch-stack.sh` 记录过 pid、且命令行匹配预期组件的
frontend、backend 和 stock 专用 bridge。它不会默认停止旧的
`deepseek-web-bridge.pid`，因为那个 pid 可能属于会议/人工分析共用的主 bridge。

如确需清理旧版 stock stack 留下的通用 bridge pid，显式执行：

```bash
scripts/stop-stock-watch-stack.sh --include-legacy-bridge
```

这个参数可能停止 `8789` 上的主 bridge，使用前请确认会议/人工分析不依赖它。

## 可选：安装 launchd 守护

如果希望登录后自动拉起并持续巡检：

```bash
scripts/install-stock-watch-launchd.sh install
```

查看状态：

```bash
scripts/install-stock-watch-launchd.sh status
```

卸载：

```bash
scripts/install-stock-watch-launchd.sh uninstall
```
