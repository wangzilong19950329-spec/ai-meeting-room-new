# 股票盯盘统一分析 v17 实施规范

日期：2026-07-16

状态：实施中。本规范合并连续主力意图、L0 风险结构、技术分析、异动判断和通知治理。

## 1. 不变量

1. 同一股票、周期和 canonical observation 最多提交一次不可逆模型请求。
2. R01-R04 只由本地确定性代码计算；模型不能创造或改写模式命中事实。
3. `GENERAL_LEGACY` 是显式兼容例外；新规则必须声明 `L0_ADVISORY` 或 `RISK_PATTERN_REQUIRED`。
4. `RISK_PATTERN_REQUIRED` 没有本地 triggered pattern 时，模型结果不能打开通知。
5. `alertTriggerConfidence` 是当前规则命中信心，不是校准后的涨跌概率；`riskReviewConfidence` 只表示模型对风险结构解释的可信度。
6. 每个 observation 先验证旧 active tests，再创建新 tests，禁止同一 observation 自证。
7. 无法确认网页请求是否已提交时状态为 `UNKNOWN`，不得自动重试。

## 2. 数据链

`canonical quote -> freshness/source gate -> completed daily bars -> R01-R04 -> fact observation -> old active-test verdict -> new tests -> one stock-analysis-v1 request -> receipt -> notification projection`

canonical fact 只包含白名单行情字段、本地派生量、R01-R04、数据质量和 observation 时间。`asOf/reason/session/runSlot` 等易变运行字段不参与 observation key。

## 3. 规则能力

| 能力 | 本地模式 | 模型能否独立触发 | 通知条件 |
| --- | --- | --- | --- |
| `GENERAL_LEGACY` | 可无 | 可以，兼容旧规则 | 旧规则逻辑，标记 `LEGACY_MODEL_RULE` |
| `L0_ADVISORY` | 必须计算 | 不可以 | 仅既有结构化 quote 规则可触发；L0 用于解释 |
| `RISK_PATTERN_REQUIRED` | 必须计算 | 不可以 | 至少一个 R01-R04 为 `triggered` |

配置缺失能力字段时只映射为 `GENERAL_LEGACY`；未知能力、冲突能力或冲突 trigger mode 必须在抓行情和调模型前失败。

## 4. 单次请求和回执

持久化任务以 `submission_key` 唯一约束预占。请求带稳定的 `submissionKey/requestDigest/purpose=stock-analysis-v1`。一次 HTTP 调用后写入响应哈希、原文、解析状态、provider 状态和完成原因。解析失败、超时、连接中断均不做第二次模型提交；人工重新分析必须生成新的显式 generation，且保留与原任务的关联。

## 5. 连续验证

每个命中的确定性模式生成固定 active test。后续 observation 按 `metricPath/operator/target` 程序化求值，保存 `SUPPORTED/WEAKENED/FALSIFIED/MIXED/UNRESOLVED`。模型只能解释这些 verdict 如何收窄假设，不能覆盖 verdict。预测涨跌的 T1/T5 所有权继续属于 `StockWatchPredictionService`；模式观点验真属于 active-test ledger。

## 6. 发布门槛

1. MySQL 初始化、H2 smoke schema 和运行时 initializer 同步。
2. 单元测试覆盖 R01-R04、盘中/盘后口径、过期 quote、HK 跳过、规则能力冲突、重复 observation、不自证和并发幂等。
3. 真实股票冒烟证明：只取一份 canonical quote；本地事实落库；第二 observation 能验证第一 observation；单次模型提交有 receipt；通知来源可追踪。
4. 冒烟问题先统一交给独立 Challenge 和 Optimize，只修复审核确认的问题，再重复真实数据冒烟。
