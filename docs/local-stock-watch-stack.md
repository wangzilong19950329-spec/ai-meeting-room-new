# 本地股票盯盘一键启动

股票盯盘的 DeepSeek 网页分析链路不是单进程：

```text
frontend 3009 -> backend 8127 -> DeepSeek bridge 8789 -> Chrome debug 9333 -> DeepSeek 网页
```

以前只启动 backend 时，定时技术分析仍会按半小时触发，但 `127.0.0.1:8789`
没有 bridge 监听，于是每只股票都会落一条 `Failed to connect to /127.0.0.1:8789`
失败记录。

## 一键启动

```bash
scripts/start-stock-watch-stack.sh
```

默认端口：

- frontend: `http://127.0.0.1:3009/stock-watch`
- backend: `http://127.0.0.1:8127`
- DeepSeek bridge: `http://127.0.0.1:8789/health`

日志默认写到：

```text
~/.aimeeting-room/logs/
```

可覆盖的环境变量：

- `AI_MEETING_BACKEND_PORT`
- `AI_MEETING_FRONTEND_PORT`
- `DEEPSEEK_WEB_BRIDGE_PORT`
- `DEEPSEEK_WEB_DEBUG_PORT`
- `AI_MEETING_ENV_FILE`
- `AI_MEETING_LOG_DIR`

## 停止由脚本启动的进程

```bash
scripts/stop-stock-watch-stack.sh
```

脚本只会停止 `start-stock-watch-stack.sh` 记录过 pid、且命令行匹配预期组件的进程。

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
