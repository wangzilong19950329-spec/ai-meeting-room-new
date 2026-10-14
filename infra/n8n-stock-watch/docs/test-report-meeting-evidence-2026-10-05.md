# MR-60 本地测试记录（2026-10-05）

## 范围

本次只验证 n8n 缺口搜索与 DeepSeek bridge 交叉审核模块。没有把 Java 会议发言链路切到 MR-60，也没有读取引用链接原文。

## 已通过

- `node --test infra/n8n-stock-watch/scripts/meeting-evidence-domain.test.js infra/n8n-stock-watch/scripts/render-meeting-evidence-workflow.test.js`：6 项通过。覆盖逐字段一致、定点复搜、三次上限、缺少搜索证明、虚构链接、超时、提供者故障，以及搜索和审核节点的排队预算。
- `sh infra/n8n-stock-watch/scripts/smoke-test-meeting-evidence.sh`：在本地 n8n 2.29.10 导入并执行真实节点图。fixture-pass 第一次审核驳回“已交付 120 GWh”，第二次搜索与审核通过“12 GWh 框架协议”；fixture-reject 连续三次驳回后 `UNVERIFIED`，推理事实为空；HTTP 503 fixture 输出 `UNVERIFIED`、零事实。全部数字和链接是虚构 fixture。
- n8n `/healthz` 返回 HTTP 200；导入的 MR-60 和 fixture runner 均为非活动状态。smoke 期间只临时发布 MR-60，结束后取消发布。

## 真实提供者验证

独立 Edge bridge 的专用资料登录后，已用 Bearer 凭据检查 `http://127.0.0.1:8795/health?prepare=true`：`ready=true`、`identityVerified=true`、`routingEligible=true`、`general.analysis` 可用。普通 Edge 或无痕窗口的登录状态不共享给该专用资料。

n8n 真实执行 103（父执行 102）通过 `http://host.docker.internal:8795/search` 调查“宁德时代 2024 年年度报告中的储能电池系统销量”。`Call Search 1` 和 `Call Review 1` 都返回 HTTP 200，均报告 `searchEnabled=true`、`isolatedSession=true`、`conversationRestored=false`、`jsonComplete=true`。两次搜索动作数分别为 28 和 29；审核给出 `PASS`，MR-60 输出 `VERIFIED`、一次搜索、一次审核、一个交叉审核事实（93 GWh）及两组引用链接。两个节点来自隔离的新会话，不能把它们称为不同模型或独立原文核验。

## 后续发现与修正

- 在 n8n 中对独立 Edge bridge 运行真实缺口时，首次 HTTP 503 使 `Call Search 1` 节点直接失败，未生成 `UNVERIFIED`。根因是 HTTP Request 节点把 `neverError` 写在 `options` 下，而当前 n8n 2.29.10 从 `options.response.response.neverError` 读取该参数。
- 已修正全部六个 HTTP 节点并重新导入 MR-60。新增 HTTP 503 fixture；`sh infra/n8n-stock-watch/scripts/smoke-test-meeting-evidence.sh` 通过，确认 503 时流程完成、输出 `UNVERIFIED`、`verifiedFacts=[]`、搜索一次且不调用审核。
- 登录后第一次真实执行 101 搜索成功，审核请求却返回 HTTP 429 `bridge_queue_timeout`。bridge 单账号每次完成后至少冷却 45 秒，MR-60 原先只允许排队 30 秒。节点现允许排队 90 秒；执行 103 的审核已成功等过冷却期，随后完成真实搜索。

两次 DeepSeek 搜索交叉一致只说明符合模块的审核规则，不能证明原始公告真实性或消除同模型的相关错误。

默认入口仍是 `deepseek-facade:8793`；本次成功证据来自显式传入的本机 Edge bridge canary。Java 会议流程尚未接入 MR-60，因此本记录不声称会议发言链路已使用这些事实。
