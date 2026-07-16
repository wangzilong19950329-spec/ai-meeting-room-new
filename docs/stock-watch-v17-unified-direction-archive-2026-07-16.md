# 股票盯盘 v17 统一方向存档

日期：2026-07-16

状态：决策方向存档，未形成正式 v17 实施规范，未编码。

## 1. 存档目的

记录当前关于“连续主力意图 + L0 风险结构 + 技术分析 + 异动规则 + 邮件通知”的统一方向，避免后续继续在两份互相冲突的方案上分别开发。

本文件不是对功能已经实现、方案已经审核通过或应用已经可发布的声明。

## 2. 关联文档

1. `docs/stock-watch-continuous-main-force-intent-plan-v16.md`
   - 拥有 fact observation、active test ledger、intent evaluation/head、统一任务队列、Broker 预算和单次 `stock-analysis-v1` 请求设计。
   - 当前仍标记为等待第十六轮双审核，尚不能直接视为已通过实施规范。
2. `docs/stock-watch-risk-pattern-prefilter-email-plan-2026-07-16.md`
   - 保留 R01-R04 L0 风险公式、事实/解释分层、双 confidence、措辞边界和邮件展示设计。
   - 主 agent 最终跨方案自审结论为：不得按该文档独立编码。

## 3. 当前决策

不再继续修补独立 L0 方案，也不把当前 v16 作为一个不可拆分的大改动直接落地。

下一版应合并为唯一 v17 实施规范，所有权固定如下：

1. v16 或其后继架构负责：
   - canonical fact observation；
   - 连续 active tests 与程序化 verdict；
   - intent evaluation/head；
   - 持久化任务、账号预算、重试与 ACK；
   - 同股同 observation 的单次模型请求。
2. L0 addendum 负责：
   - R01-R04 确定性公式；
   - `riskPatternObservations/riskPatternGuidance`；
   - `alertTriggerConfidence/riskReviewConfidence` 分离；
   - L0 不能证明操纵、坐庄、对倒、账户组等事实的语义边界；
   - 邮件中的本地事实、程序复核和模型解释分栏。
3. 禁止再创建：
   - 第二套 14 天历史 ledger；
   - 第二套 intent maturity/verdict；
   - 逐规则重复 risk 计算；
   - 独立意图模型请求；
   - 从邮件去重状态反推连续分析状态。

## 4. 分阶段实施方向

### M1：本地影子数据链

1. canonical fact 入库。
2. 每个唯一 observation 只计算一次 R01-R04。
3. 建立持久化 active tests，使 3/5/20 日判断能跨越 14 天冷启动窗口继续验证。
4. 不调用 DeepSeek，不发送新邮件，不改变现有告警结果。
5. 使用 `002979.sz` 雷赛智能的真实历史快照回放，证明第二个 observation 能验证第一个 observation，重复快照不推进状态。

### M2：统一模型请求

1. DAY 技术分析、同股规则判断和 intent update 合并为一次 `stock-analysis-v1`。
2. 统一走股票专用 8791 Broker 和账号级队列/预算。
3. 同一股票同一 observation 最多一次不可逆网页提交。
4. 重复 observation 不新增请求。
5. `RISK_PATTERN_REQUIRED` 且没有本地 `status=triggered` pattern 时确定性 fail closed，不占用网页请求。
6. 首阶段保持 `notify=false`，先验证持久化、状态推进、限速和失败恢复。

### M3：邮件与正式迁移

1. 邮件只能读取已提交的 canonical fact、本地 risk observations、程序 test verdict 和完成本地校验后的模型解释。
2. 模型叙事固定标记为“模型解释（非事实）”。
3. `COMMITTED_LAGGED` 不发送实时异动邮件。
4. GENERAL 存量规则保持原有触发和去重语义。
5. 最后才开启通知，并用真实雷赛智能执行一次低频 DeepSeek 冒烟和一封真实邮件验证。

## 5. 必须保持的不变量

1. 同一 observation 不重复消费 active tests。
2. 未发送邮件的异动判断也必须进入下一轮连续验证状态。
3. 模型失败不能阻止本地事实和已有 tests 推进。
4. 历史分析、prior verdict 和模型叙事不能直接成为本轮告警触发事实。
5. `TWENTY_DAY` claim 不能因为源记录离开 14 天冷启动窗口而丢失。
6. L0 风险置信度不能降低既有结构化告警置信度。
7. 达到账号预算或冷却上限时只能排队或 fail closed，不能自动切号、提速或回退其它 DeepSeek 通道。
8. 邮件不能把程序复核或模型意图推断表述为主力真实心理或操纵事实。

## 6. 编码前发布门

正式 v17 必须至少证明：

1. 连续状态只有一个 owner，没有双 ledger、双 verdict 或双邮件事实源。
2. 同股同 observation 的 technical/rules/intent 只产生一次模型业务请求。
3. 重复 observation 的新增网页 reservation 数为 0。
4. 第二个真实 observation 能验证第一个 observation，且证据时点严格晚于观点可用时点。
5. 20 日 claim 在 14 天冷启动范围之外仍能终局。
6. GENERAL 规则兼容 fixture 不变。
7. 邮件事实段不读取 raw response 或未经校验的 belief update。
8. 真实冒烟不污染生产通知、账号 journal 或生产状态水位。

## 7. 当前未决边界

1. v16 本身尚未完成第十六轮双审核，因此本存档不等于选择了 v16 的全部具体表结构和 Broker 实现细节。
2. 正式 v17 仍需删除或改写 v16 与 L0 文档之间冲突的 owner、history、prompt、邮件和测试条款。
3. M1/M2/M3 是交付顺序，不是三个可长期并存的架构；最终仍只有一条统一生产链。
4. 在正式 v17 完成并通过审核前，不修改业务代码。

## 8. 下一步

以本存档为边界，整理唯一的 `stock-watch-unified-analysis-plan-v17.md`。该文档应合并而不是叠加 v16 与 L0 内容，并以 M1 影子模式作为第一个可验证开发里程碑。
