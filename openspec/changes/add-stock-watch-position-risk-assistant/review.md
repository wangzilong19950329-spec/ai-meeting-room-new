# 统一方案审核与裁决

日期：2026-10-05。范围：设计目标、当前静态证据、合同覆盖和实施路径；不是业务实现、实际行情、通知送达或风险效果验收。

权威范围是用户最新确认“本轮只完成统一设计方案”：保留原bug修复，新增持仓风险助手，人工选股入场与人工退出，完整预测次级但保留，意图研究并行。详见 [统一路线](../../../docs/design/stock-watch-unified-delivery-plan-2026-10-05.md)、[详细设计](design.md) 和 [v1审核包](audit-packet.json)。

## 1. 方法与证据边界

- 使用conclusion-auditor的目标门、证据/反证门、结论门、路径比较和主代理裁决。
- Challenge为上下文隔离子代理；Stage A只读取权威用户目标与必要现状，不读取新方案；冻结义务后，Stage B读取方案/规格/任务/审核包并核对静态源证据。
- Optimize为另一个上下文隔离、只读路径审核子代理，不代替Challenge正确性审查。
- 同模型审核仍可能有相关错误，不能称完全独立或全局最优。Stage B也看到了主代理材料。
- 两位审核员均未写文件、写业务库、启动测试/业务服务、调用模型、发送通知或交易。主代理本轮写入仅限设计工件；当前git工作区包含很多既有/并发改动，不能仅用最终dirty列表证明本轮写集。
- CodeGraph为导航，关键源/SQL另用原始读取核验。实时库存、供应商资格/时延、实际部署与执行效果仍待各阶段证据。

## 2. Stage A冻结义务

| 义务 | 设计落点 |
| --- | --- |
| 保留全部旧修复、本轮仅设计 | 原79项未实施；新57项未实施；总路线逐包映射 |
| 已有持仓优先、人工退出 | D03批次/事件/可卖，D11授权API，D12人工工作台 |
| 及早与及时分别有证据 | D05数据资格，D06早期候选与保护条件，D07全链时点 |
| 持仓与可卖信息须有新鲜度 | D03库存确认/过期，资格估计不等于券商保证 |
| 危险窗、反证/失效与执行边界 | D06/D08/D13，不限定只有当日兑现 |
| 全机会、误报漏报与未触发样本 | D10/D14，缺失不填安全，执行成本不隐藏 |
| canonical单提交与V1冻结 | D04/D10，旧方向/意图/研究继续独立 |
| 人可用信息与来源而非含糊分数 | D02/D12，级别、验证状态、覆盖、可卖、通知分开 |

Stage A另发现仓库已有n8n/Postgres持仓压力原型。裁决：**属实**。D Context已补充其symbol/market数量模型与LOCAL_QA_FIXTURE边界；不能说全仓库从无持仓，也不能把原型自动当真实账户或与新owner双写。

## 3. Challenge Stage B与裁决

首轮结论：**通过，限定为“设计可进入分能力preflight”**。无preflight入口阻塞；不表示实现已完成或所有合同已闭合，也不授权启用通知。

| 发现 | 主代理裁决 | 文档修订与验收定位 |
| --- | --- | --- |
| 原子发布/episode CAS不足以阻止过时持仓输入发布当前提醒 | 属实 | D09锁position并核版本/generation/ACTIVE/行情资格；过时计算NOT_PUBLISHED，不生成义务/availableAt；任务4.2、delivery并发场景 |
| MISSED没有availableAt，需独立标签窗和召回匹配 | 属实 | D10先冻结scheduledAt共同结果协议；D14按实际warning时间匹配风险首发生，缺路径UNSCORABLE，不伪造发布；任务6.1/6.8、evaluation漏跑场景 |
| 部分退出的两侧数量/剩余仓位未写清，可能金额符号错误 | 属实 | D13两边同Q，或剩余估值相消后同qExit；任务6.5/6.8、evaluation部分可卖场景 |

上述三项修订已完成Challenge限定静态复核，结论为**三项设计合同均已闭合，相关范围未发现新增直接矛盾**。复核再次核对D09/D10/D13/D14、相应任务和三条scenario。这里“闭合”只指反例已有明确设计响应与可测试预期，不证明实现并发安全、数据资格或效果。

## 4. Optimize路径比较与裁决

在正确性和目标门成立的前提下，未发现路径级blocking。对以下候选作有限比较：全链高频AI、独立微服务、直接提升n8n原型/双写、现有Java/MySQL领域内独立快路径，以及更小分阶段子集。

| 建议/结果 | 主代理裁决 | 采用路径 |
| --- | --- | --- |
| material_improvement：报价保护的独立最小闭环 | 属实 | D05分报价/区间/结果路径门；后两门不阻断报价保护；D10八表按N1/N2/N3/N5建，首批不齐建 |
| material_improvement：前移预注册和影子积累 | 属实 | D14及任务7.1/7.2随N2/N4资格前向积累；N5计分、N6盲测批准，非提前启用推断 |
| material_improvement（条件性）：R0确定真实通知场景 | 属实；用户是否常驻待补证 | N0先选常驻/离线路径，N3验收；邮件接受/浏览器权限不冒充离线及时看到 |
| no_better_alternative_found：Java/MySQL owner比n8n原型更合适 | 属实，限已比较路径 | 现有认证/工作台/交付增量复用；n8n以后只经owner接口，不导入QA或双写 |
| no_better_alternative_found：隔离高频观测但不建微服务 | 属实，限已比较路径 | 同JVM内有界调度/持久化lease，保持canonical cadence/hash及V1隔离 |

较小保护子集不是完整助手交付，也不能替代用户关注的提前危险识别。原79项和新增57项全部保留；未因优化将其他能力标为完成或删除。

## 5. 结构验证与结论边界

已执行：

```text
openspec validate improve-stock-watch-decision-reliability --strict
openspec validate add-stock-watch-position-risk-assistant --strict
python3 ~/.codex/skills/conclusion-auditor/scripts/auditctl.py validate <new-change>/audit-packet.json --format json
```

结果均通过。最终核对：原方案6个capability、33条requirement、44个scenario、79项未实施任务；新增助手5个capability、34条requirement、52个scenario、57项未实施任务。19份Markdown本地链接无缺失、无行尾空白；所有requirement均有scenario。原始git diff --check通过，但不覆盖untracked全文，后者由上述逐文件检查补足。

OpenSpec状态中的artifact done/isComplete只表示方案工件齐备；审核包v1结构合格不是receipt-backed严格证据认证。没有运行Java/frontend业务测试，因为没有实施业务代码；不拿格式检查替代业务链证明。

**当前可支持**：按统一路线进入分能力实施前验证，先证明报价/持仓/交付工程子集；旧修复继续，早期候选可按冻结协议并行影子积累。**仍待补证**：真实源身份/年龄和结果路径、实际库存/券商可卖、用户保护参数及误报效用、离线渠道、前向增量、实际避损和运行版本。

仅设计交付不等于风险功能已可用，不保证未来准确率提升、尾部风险规避或净收益。
