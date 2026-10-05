# MR-60 本地测试记录（2026-10-05）

## 范围

本次只验证 n8n 缺口搜索与 DeepSeek bridge 交叉审核模块。没有把 Java 会议发言链路切到 MR-60，也没有读取引用链接原文。

## 已通过

- `node --test infra/n8n-stock-watch/scripts/meeting-evidence-domain.test.js`：5 项通过。覆盖逐字段一致、定点复搜、三次上限、缺少搜索证明、虚构链接、超时和提供者故障时的拒收。
- `sh infra/n8n-stock-watch/scripts/smoke-test-meeting-evidence.sh`：在本地 n8n 2.29.10 导入并执行真实节点图。fixture-pass 第一次审核驳回“已交付 120 GWh”，第二次搜索与审核通过“12 GWh 框架协议”；fixture-reject 连续三次驳回后 `UNVERIFIED`，推理事实为空。全部数字和链接是虚构 fixture。
- n8n `/healthz` 返回 HTTP 200；导入的 MR-60 和 fixture runner 均为非活动状态。smoke 期间只临时发布 MR-60，结束后取消发布。

## 尚未通过的真实提供者验证

通过现有 Bearer 凭据向 `http://127.0.0.1:8793/search` 发起真实、隔离的新会话 JSON 搜索，请求返回 HTTP 503 `login_required`。因此尚无真实 DeepSeek 搜索和审核完成的证据。需要先恢复 DeepSeek 浏览器登录，再执行真实提供者 canary；不能用 fixture 的成功替代这一环。

两次 DeepSeek 搜索交叉一致只说明符合模块的审核规则，不能证明原始公告真实性或消除同模型的相关错误。
