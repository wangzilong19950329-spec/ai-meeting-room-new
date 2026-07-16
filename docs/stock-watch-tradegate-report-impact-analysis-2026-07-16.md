# A 股交易质量闸门报告对股票盯盘 v17 的影响分析

日期：2026-07-16

状态：已完成独立 Challenge / Optimize 多轮审核；最终两者均为 `PASS`，阻断项为 0。只分析，不修改业务代码。

输入报告：`/Users/wangzilong/Downloads/A股交易质量闸门与同类开源项目深度检索报告.pdf`

目标方案：`docs/stock-watch-v17-unified-direction-archive-2026-07-16.md`

## 1. 结论先行

报告对 v17 有借鉴价值，但不能整体照搬。它描述的 TradeGate 是“执行层之前的交易放行/否决闸门”，当前项目则是“行情采样、盯盘规则、模型分析、连续验证和邮件通知”，没有下单接口、订单风险契约或放行责任。因此：

1. **应立即写入正式 v17 规范**：按 capability 判定的数据质量门、分阶段审计契约、确定性结果不依赖模型、结果版本化与事后评估。
2. **应作为影子研究扩展**：历史相似失败窗口、按模式/版本分桶的经验校准、事件 ETL；相似窗口允许在 feature schema 与 `outcomeDefinitionVersion` 同时冻结后前移一个不落库、不调模型的极小离线 PoC，但不能进入生产 prompt、邮件或告警结果。
3. **当前明确不做**：下单前 hard veto、用 L0 数据认定主力操纵、让 DeepSeek 生成可当作概率的 confidence、增加第二套 T+N 评估器、为这些能力增加模型请求。
4. 报告没有推翻 v17 的事实优先、程序验真、单次模型请求和失败不阻塞本地状态推进方向；但当前自然语言规则仍可能由模型判断并打开通知门，所以不能笼统声称现有 v17 已经实现“模型只解释”。本文已锁定边界：新 L0/质量链的模型 finding 只能 advisory；只有存量 GENERAL 规则可作为显式隔离的 `LEGACY_MODEL_RULE` 兼容例外。
5. 报告暴露了 v17 尚未明确的两个缺口：`dataQuality` 还不是统一任务的 capability 准入门；模型、规则、特征、快照和输入输出哈希还没有形成“冻结 request envelope + 追加 result receipt + 派生只读 audit projection”的统一审计契约。

## 2. 范围与术语边界

| 名称 | 本文定义 | 是否进入当前 v17 |
|---|---|---|
| 数据质量门 | 在计算风险模式或形成模型任务前，确定性检查必需字段、时效、来源一致性、降级状态 | 是 |
| 分析质量门 | 对分析结果做 schema、证据路径、语义边界和版本一致性校验 | 是 |
| 通知门 | 决定是否允许形成邮件通知；不代表交易建议 | 是，沿用并加强 |
| TradeGate hard veto | 直接决定订单是否放行 | 否；当前系统没有订单执行边界 |
| 校准概率 | 在冻结版本和足够样本上，经可靠性评估后具有频率解释的概率 | 影子研究；当前 confidence 不属于此类 |
| 相似失败窗口 | 只用触发时点可见特征检索历史窗口，再观察其未来结果 | 延后；先建立版本化事实和标签 |

语义不变量：L0 风险模式只能描述“失败突破风险、量价效率下降、高开回落、上方抛压反复”等可观察结构，不能证明诱多、对倒、坐庄、账户协同或主力真实心理。

## 3. 报告主张核验

### 3.1 已由一手资料支持

| ID | 报告主张 | 一手资料核验 | 判定 |
|---|---|---|---|
| RPT-01 | AlphaSift 把硬筛、可选 LLM 软判断、运行保存和 T+N 评估分层 | AlphaSift design/README 明确硬条件由代码执行、LLM 不负责数值阈值和技术指标、支持 `--no-llm`、保存 run 和 T+N evaluation | 属实，可借鉴边界 |
| RPT-02 | SignalForge 有 schema、prompt 版本、成本记录和 deterministic stub | 仓库 README/源码说明使用 Zod schema、prompt version、token/cost tracking、`AI_DISABLE=true` stub；该 stub 是演示假数据，不是生产级 fallback | 工程模式属实，但仅借鉴思想 |
| RPT-03 | a-stock-data 提供多源 A 股适配和降级 | 当前仓库自述 10 层、43 endpoints、15 sources，并记录 fallback 和数据源失效治理 | 属实，可作为候选适配器参考 |
| RPT-04 | Qlib 擅长特征、实验和回测治理 | Qlib 官方仓库提供 workflow、`qrun`、Alpha158/360、backtest/evaluation | 属实，但不应直接嵌入在线主链 |
| RPT-05 | 上述项目都不是完整的下单前 TradeGate | 核验到的项目定位分别是候选筛选、公告事件、数据工具和量化研究，没有发现覆盖本报告全部能力的项目 | 在已核验集合内成立；不是对全开源世界的完备证明 |

一手资料：

- AlphaSift: https://github.com/ZhuLinsen/alphasift
- AlphaSift design: https://github.com/ZhuLinsen/alphasift/blob/main/docs/design.md
- SignalForge: https://github.com/devlive-community/signalforge
- a-stock-data: https://github.com/simonlin1212/a-stock-data
- Qlib: https://github.com/microsoft/qlib

### 3.2 需要降级的报告表述

| ID | 原表述或倾向 | 降级原因 | 本文采用的表述 |
|---|---|---|---|
| RPT-D01 | “没有整体实现 TradeGate 的开源项目” | 检索不能证明全世界不存在；仓库也会变化 | 在报告及本轮核验集合中未发现完整实现 |
| RPT-D02 | “相似窗口是核心模块，应尽早做” | 当前项目尚无稳定的版本化特征和风险模式结果标签，直接检索容易产生泄漏与叙事偏差 | 先完成 M1 事实/结果账本，再以影子研究验证增益 |
| RPT-D03 | “至少需要部分 L2 才能识别主力套路” | 这是工程推断，不是行业定理；L2 也不能证明账户主体或意图 | L2 只能增加可观察盘口证据，仍须限制语义 |
| RPT-D04 | “概率由确定性模型产生” | 确定性计算不自动等于校准；校准依赖冻结标签、无泄漏样本和样本外评估 | 规则输出 deterministic score/status；只有通过校准门的字段才叫 probability |
| RPT-D05 | “复用 SignalForge” | 仓库为 UNLICENSED，不能默认复制代码 | 只借鉴公开工程模式；未经许可不复制代码 |
| RPT-D06 | “hard veto” | 当前项目无订单执行契约 | 只形成分析质量门和通知门，不宣称交易放行/否决 |

## 4. 当前系统代码证据

| ID | 当前事实 | 代码证据 | 对报告建议的影响 |
|---|---|---|---|
| CUR-01 | 市场数据 bundle 已区分 required/optional missing，记录每个来源状态和请求响应哈希 | `EastmoneyMarketDataService.java:61-149,477-502,532,559-598` | 已有雏形；应统一为显式 `DataQualityGateResult`，并对白名单 trace 脱敏。当前 trace 含完整 URL/params，不能原样进入审计投影 |
| CUR-02 | 技术分析已保存 provider、promptHash、dataSnapshotJson、raw response、target/conversation | `StockWatchTechnicalAnalysisService.java:368-410,424-433` | 可演进为 run manifest；不是从零建设审计链 |
| CUR-03 | 现有预测会保存模型给出的 probability，并在 T1/T5 等到期后计算方向、区间、失效、误差和 Brier | `StockWatchPredictionService.java:43-92,108-199,214-249` | 不新增第二套 forecast evaluator；复用纯计算代码，但 forecast 与 pattern/claim 保持领域 owner 分离 |
| CUR-04 | 现有模型 confidence 直接读取模型 JSON；结构化规则 confidence 是固定启发式数值，两者取 max 后可驱动通知 | `StockWatchAiJudgeService.java:110-119,217-245` | 当前 confidence 不可称为校准概率；正式 v17 必须标注语义与 calibration status |
| CUR-05 | 历史分析若被模型用于本轮触发，会被程序撤销触发 | `StockWatchAiJudgeService.java:248-260` | 已符合“历史只验证/解释，不是本轮触发事实”的方向 |
| CUR-06 | v17 存档要求 canonical fact、active tests、模型失败不阻塞事实推进、同 observation 一次模型请求 | `stock-watch-v17-unified-direction-archive-2026-07-16.md:26-45,49-83` | 报告强化而非替代现有主架构 |
| CUR-07 | L0 方案已定义 R01-R04、freshness、completed bars、风险语义和双 confidence | `stock-watch-risk-pattern-prefilter-email-plan-2026-07-16.md:58-157` | 保留公式与边界；需要把“confidence”再分为 heuristic 与 calibrated |

## 5. 采用决策矩阵

| ID | 候选能力 | 当前价值 | 采用决策 | 阶段 | 验收证据 |
|---|---|---:|---|---|---|
| A01 | 显式数据质量门 | 高 | 纳入正式 v17 | M1 | 同一 fixture 对缺字段、陈旧、未来时间、来源冲突输出稳定 reason code；GENERAL 兼容结果不变 |
| A02 | 分阶段审计契约与只读 audit projection | 高 | 修订后纳入正式 v17；不建 manifest 状态机 | M1/M2 | 冻结 request envelope、追加 result receipt；只读投影可追溯 schema/feature/rule/prompt/provider/model/snapshot/request/response/fallback |
| A03 | 模型关闭/失败仍有确定性结果 | 高 | 已是方向，补成验收项 | M1/M2 | Bridge 断开时事实、风险 pattern、tests 和 disposition 正常持久化，模型解释明确 unavailable |
| A04 | 固定 outcome owner 并复用评估代码 | 高 | 修订后纳入；不建统一新 ledger，不把 prediction 表扩成通用表 | M1 后 | pattern/claim 归 active tests；forecast 归 PredictionService；派生统计不拥有 verdict |
| A05 | 置信度语义治理 | 高 | 纳入正式 v17 | M1/M3 | 每个 confidence 独立携带 `sourceKind=MODEL_REPORTED|RULE_HEURISTIC|CALIBRATED_MODEL`、`calibrationStatus=UNAVAILABLE|INSUFFICIENT_SAMPLE|CALIBRATED`、版本和样本量；未校准值不显示为概率 |
| A06 | 保存失败样本与脱敏降级路径 | 高 | 纳入正式 v17 | M1/M2 | source/model/schema failure 与成功结果同样可回放；trace 使用白名单、脱敏和完整 digest，不包含凭据或敏感 query |
| A07 | 历史相似失败窗口 | 中，潜力高 | 生产延期；允许 feature schema 与 `outcomeDefinitionVersion` 同时冻结后做极小只读离线 PoC | M1 并行研究/后续生产 | 无未来特征；特征/标签同版本；walk-forward；与简单基线比较有稳定增益 |
| A08 | 两阶段相似检索 | 中 | 随 A07 | 研究阶段 | 先按市场阶段/波动/模式硬过滤，再用冻结特征 rerank；输出邻居和距离 |
| A09 | a-stock-data 多源适配 | 中 | 先抽象 adapter contract，再做离线候选验证 | 后续 | 许可证、字段单位、时效、限频、故障降级和与 Eastmoney 一致性通过 fixture |
| A10 | Qlib 实验治理 | 中 | 只用于离线研究候选 | 后续 | 能复现同一 dataset/version/result；不进入在线 Bridge 路径 |
| A11 | SignalForge 事件 ETL 方式 | 中 | 借鉴 schema/outbox/version/cost 思路，不复制代码 | 后续 | 自有实现且许可证来源清楚；事件时点可审计 |
| A12 | AlphaSift 硬筛/软解释边界 | 高 | 对新 L0/数据质量/程序 tests 立即吸收 | 立即 | 数值阈值、指标和程序 verdict 不由 LLM 改写；新模型 finding 不能单独打开通知门；存量 GENERAL 模型规则仅作为显式兼容例外 |
| A13 | 真正单次模型提交 | 高 | 作为正式 v17 阻断门 | M2 | schema invalid/incomplete 直接落失败或 deterministic-only，不调用第二次 Bridge 修复请求 |
| N01 | 订单 hard veto | 低且越界 | 不采用 | 不适用 | 只有未来存在正式订单执行契约才重新立项 |
| N02 | L0 “主力套路/操纵” veto | 危险 | 不采用 | 不适用 | 即使增加 L2，也不能把主体和心理当事实 |
| N03 | 模型生成精确概率/最终否决 | 危险 | 不采用 | 不适用 | 模型仅解释、列风险和待核查项 |
| N04 | 直接 fork SignalForge/AlphaSift/Qlib | 低性价比 | 不采用 | 不适用 | 仅复用适合的思想或有许可证的边界模块 |
| N05 | 为报告能力增加第二次模型请求 | 与账号预算冲突 | 不采用 | 不适用 | 同股同 observation 仍最多一次业务模型提交 |

## 6. 对正式 v17 的具体增补建议

### 6.1 M1/M2：分阶段审计契约，不新增 manifest owner

审核否决“新增一个可变且拥有状态的 AnalysisRunManifest 表”。请求冻结和响应产生于不同时间点，强塞进一个可更新对象会破坏不可变语义，另建状态机也会成为第二 owner。

正式 v17 应固定以下 owner：

| 数据 | 唯一 owner |
|---|---|
| canonical facts、dataQuality、R01-R04 observation | fact observation |
| pattern/claim 的程序 outcome | active test + immutable test result |
| forecast outcome | 现有 `stock_watch_prediction` / `StockWatchPredictionService` |
| request identity、payload digest、Broker/ACK 状态 | task receipt |
| response hash、模型结果和 provider trace | intent/model evaluation |
| 邮件内容 | 只读投影，不拥有状态 |

在这些 owner 之上提供派生的只读 audit projection。下面只是投影形状，不是新表、ledger 或状态机：

```json
{
  "schemaVersion": "analysis-run-manifest-v1",
  "observationKey": "sha256:...",
  "snapshotId": "...",
  "snapshotHash": "sha256:...",
  "dataQuality": {
    "status": "PASS|DEGRADED|FAIL",
    "reasonCodes": [],
    "sourceStatuses": {}
  },
  "versions": {
    "canonicalFact": "v1",
    "feature": "l0-risk-v1",
    "ruleSet": "sha256:...",
    "prompt": "stock-analysis-v1"
  },
  "provider": {
    "route": "deepseek-web-bridge-8791",
    "modelSelector": "web-ui",
    "temperature": "unavailable",
    "topP": "unavailable"
  },
  "requestEnvelope": {
    "requestDigest": "sha256:...",
    "frozenAt": "..."
  },
  "resultReceipt": {
    "responseHash": "sha256:...",
    "completedAt": "..."
  },
  "fallbackMode": "NONE|DETERMINISTIC_ONLY",
  "confidences": {
    "alertTrigger": {
      "value": null,
      "sourceKind": "MODEL_REPORTED|RULE_HEURISTIC|CALIBRATED_MODEL",
      "calibrationStatus": "UNAVAILABLE|INSUFFICIENT_SAMPLE|CALIBRATED",
      "version": "...",
      "sampleSize": null
    },
    "riskReview": {
      "value": null,
      "sourceKind": "MODEL_REPORTED|RULE_HEURISTIC|CALIBRATED_MODEL",
      "calibrationStatus": "UNAVAILABLE|INSUFFICIENT_SAMPLE|CALIBRATED",
      "version": "...",
      "sampleSize": null
    }
  }
}
```

约束：网页 Bridge 未暴露 model/temperature/top_p 时记录 `unavailable`，不能伪造参数；审计投影不存账号凭证、cookie、完整 URL query 或原始 params。当前 `EastmoneyMarketDataService` trace 必须先改为字段白名单、敏感键脱敏和完整 `sha256:` digest，再允许进入投影。

`DataQualityGateResult` 应在风险模式计算与模型任务构建之前生成，但不同 capability 使用不同 eligibility：风险模式失败不能无条件改变 GENERAL 存量规则语义。输出至少包含字段完整性、quote 时效、未来时间、基线 bar 数、quote/bar 日期一致性、来源降级和单位策略。

capability 行为矩阵固定为：

| Capability | `PASS` | `DEGRADED` | `FAIL` |
|---|---|---|---|
| `GENERAL_LEGACY` | 按存量字段和通知语义评估、持久化、入队 | 若该规则自身 required fields 齐全则继续，记录 degradation；R01-R04 缺口不得阻塞 | 仅在该 GENERAL 规则自身 required fields 缺失时 skip；不得因 L0 专属缺口 fail |
| `L0_ADVISORY` | 计算并持久化，可附着到已有确定性触发事实 | required fields 齐全时计算并标记 degraded，可附着展示 | 持久化 `skipped+reasonCodes`；不入模型队列、不通知 |
| `RISK_PATTERN_REQUIRED` | 计算并持久化；只有本地 `status=triggered` 才可入队并通过既有通知策略 | required fields 齐全时允许确定性计算/入队，邮件必须展示 degradation | 持久化 `skipped+reasonCodes`；fail closed，reservation=0，不通知 |

通知边界同时固定：新 `L0_ADVISORY/RISK_PATTERN_REQUIRED` 的模型 finding 只能解释已存在的本地 observation/test verdict，不能单独把 `shouldNotify` 从 false 改为 true。为满足 v17 的 GENERAL 兼容门，存量 GENERAL 自然语言规则暂时保留旧通知语义，但必须标记 `triggerEvidenceSource=LEGACY_MODEL_RULE` 和 `sourceKind=MODEL_REPORTED`，不得与确定性 `STRUCTURED_RULE/RISK_PATTERN/TEST_VERDICT` 合并成同一事实；新增规则默认不能选择该兼容模式，后续迁移另行立项。

### 6.2 M1 之后：按领域复用结算能力，不建立“万能 outcome ledger”

1. `StockWatchPredictionService` 保持 forecast outcome 的唯一 owner，复用其 K 线读取、方向/区间/失效/误差/Brier 计算代码，不把 prediction 表扩成通用表。
2. R01-R04 产生的后续可验证命题进入 v17 active tests；active tests 是 pattern/claim outcome 的 owner。冻结 `patternVersion`、触发时事实、目标窗口和程序定义的 outcome，不让模型决定成功/失败标签。
3. 校准/可靠性统计是派生读模型，只读取已终局结果，不拥有或改写 verdict。
4. 首先输出分桶统计：样本数、命中后回撤/推进分布、base rate、Brier 或适用的 scoring rule、版本和市场阶段。
5. 每个 confidence 分别记录来源与校准状态：模型自报为 `sourceKind=MODEL_REPORTED, calibrationStatus=UNAVAILABLE`；规则启发值为 `RULE_HEURISTIC, UNAVAILABLE`；有标签但样本不足为相应 sourceKind + `INSUFFICIENT_SAMPLE`；只有样本外校准通过后才是 `CALIBRATED_MODEL, CALIBRATED`。禁止把前三类显示成“75% 成功率”之类精确概率。

### 6.3 历史相似失败窗口的发布门

feature schema 与 `outcomeDefinitionVersion` 同时冻结后可前移一个极小只读离线 PoC；生产采用仍须满足以下条件：

1. 查询向量只含 anchor 时点已知字段；未来收益、后续 verdict、事后公告不得进入向量。
2. featureVersion、`outcomeDefinitionVersion`、单位、复权、市场阶段和缺失值策略同时冻结；不得看完结果后重定义“失败”。
3. outcome 只作为邻居标签，不参与召回或 rerank 特征。
4. 采用 walk-forward/时间切分；禁止随机切分制造未来泄漏。
5. 与“同 pattern base rate”“最近 N 次均值”等简单基线比较；没有稳定增益就不进入 prompt。
6. PoC 只选择一个模式，不落生产库、不调用 DeepSeek、不影响 M1；第一阶段只输出离线报告中的邻居、距离、触发事实和后续结果。
7. 生产影子阶段也不触发邮件、不改变 deterministic status、不增加 DeepSeek 请求。

### 6.4 单请求发布门

当前 `StockWatchTechnicalAnalysisService.java:375-380,582-595` 会在 incomplete 或 schema/intent 校验失败时第二次调用 Bridge。正式 v17 的“同股同 observation 一次提交”必须覆盖 schema repair：第一次响应无效时保存失败证据并走 `DETERMINISTIC_ONLY/INVALID_RESPONSE`，不得用第二次网页请求修复。

## 7. 可复用组件与许可证边界

### 7.1 当前主链直接复用

| 现有组件 | 复用内容 | 保持的 owner 边界 | 必要修订 |
|---|---|---|---|
| `EastmoneyMarketDataService` | required/optional missing、source status、request/response hash、行情 bundle | fact observation 拥有最终 dataQuality | 增加 freshness/capability eligibility；trace 白名单与脱敏 |
| `StockWatchTechnicalAnalysisService` 与现有分析记录 | snapshot、provider、prompt hash、raw/error response、conversation trace | evaluation 拥有模型结果 | prompt hash 改完整带算法 digest；M2 删除 schema repair 的第二次 Bridge 请求 |
| `StockWatchPredictionService` | K 线读取、方向/区间/失效/误差/Brier 算法和 forecast 到期调度 | 只拥有 forecast outcome | 抽出可复用纯计算函数；不把 prediction 表扩成 pattern ledger |
| v16 task receipt 设计 | request identity、冻结 payload/digest、Broker/ACK 状态 | task receipt 拥有请求生命周期 | 正式 v17 统一命名并明确脱敏字段 |
| v17 active tests | pattern/claim 的跨 observation 程序验真 | active test/result 拥有 pattern/claim outcome | 仍是待实现方案；先冻结 label 和时点规则 |

当前阶段**不需要引入外部运行时依赖**即可完成 A01-A06、A12/A13。优先复用本项目已有代码和 v16/v17 owner 设计，外部项目只解决后续离线研究或多源适配问题。

### 7.2 外部项目采用边界

| 项目 | 可借鉴 | 不建议 | 许可证/治理边界 |
|---|---|---|---|
| AlphaSift | hard screening、LLM soft layer、saved run、T+N review | 直接替换本项目规则/状态链 | Apache-2.0；仍需核对具体文件与依赖许可证 |
| SignalForge | schema、prompt version、cost ledger、stub、outbox/SSE 思路 | 复制源码或作为生产依赖 | UNLICENSED；默认没有复制/再分发授权 |
| a-stock-data | 多源 adapter、fallback、source health | 立刻替换 Eastmoney 或让模型直接调用 | Apache-2.0 只覆盖代码；还要逐源验证 API/数据的缓存、再分发、频率和商业使用条款 |
| Qlib | 离线 feature/experiment/backtest 治理 | 嵌入在线盯盘主链或直接采用通用预测 score | MIT；框架引入成本需由离线增益证明 |

最终组件判定：

1. **可作为后续代码依赖候选**：`a-stock-data` 的适配层、Qlib 的离线实验工具；必须先做隔离 PoC、commit/license 锁定和数据源条款审核。
2. **可参考设计，暂不引代码**：AlphaSift 的硬筛/软解释与 saved-run/T+N 方式。
3. **只能参考公开思想**：SignalForge；`UNLICENSED`，不复制源码、不作为生产依赖。
4. **不复用**：任何外部项目的最终 score、LLM 概率、hard veto 或“主力操纵”标签。

## 8. Claim Graph

```json
{
  "claims": [
    {
      "id": "C1",
      "claim": "报告支持并加强 v17 的事实优先、确定性判断和模型软解释边界",
      "evidence": ["RPT-01", "RPT-02", "CUR-05", "CUR-06", "A12"],
      "counterevidence": ["当前 stock-analysis-v1 仍包含模型 ruleDecisions 与 intentUpdate"],
      "status": "partially_supported",
      "falsifier": "若正式 v17 允许新 L0/质量链的模型 finding 改写指标、阈值、程序 verdict 或单独打开通知门，或未隔离 LEGACY_MODEL_RULE 兼容例外，则该吸收结论未落实"
    },
    {
      "id": "C2",
      "claim": "当前最值得补齐的是统一数据质量门和分阶段审计契约",
      "evidence": ["CUR-01", "CUR-02", "A01", "A02"],
      "counterevidence": ["现有 dataQuality、promptHash、snapshot 已覆盖一部分能力"],
      "status": "partially_supported_use_derived_projection",
      "falsifier": "若现有 owner 已能通过冻结 envelope、追加 receipt 和只读投影完整追溯版本、哈希、降级及 eligibility，则只需定义查询契约"
    },
    {
      "id": "C3",
      "claim": "当前 confidence 不能作为校准概率或 veto 依据",
      "evidence": ["CUR-04", "RPT-D04", "A05"],
      "counterevidence": ["PredictionService 已计算 Brier，但输入 probability 仍来自模型，且聚合并非校准过程"],
      "status": "supported",
      "falsifier": "提供冻结版本、足够样本、样本外可靠性曲线和校准方法后，可将特定版本字段升级为 CALIBRATED"
    },
    {
      "id": "C4",
      "claim": "相似失败窗口的生产采用应延期；feature schema 与 outcomeDefinitionVersion 同时冻结后可前移极小只读离线 PoC",
      "evidence": ["A07", "A08", "当前仓库未发现已实现的 versioned pattern outcome dataset"],
      "counterevidence": ["报告认为它是 TradeGate 核心差异能力"],
      "status": "partially_supported_runtime_db_not_verified",
      "falsifier": "若运行数据库或外部资产已有无泄漏、版本化、足量的数据集，则生产研究可提前，但仍须通过发布门"
    },
    {
      "id": "C5",
      "claim": "报告不能证明当前项目应实现订单 hard veto 或 L0 主力操纵识别",
      "evidence": ["范围边界", "RPT-D03", "RPT-D06", "N01", "N02"],
      "counterevidence": ["报告目标系统本身包含 veto"],
      "status": "supported",
      "falsifier": "只有出现独立的订单执行需求、合规责任、风险契约和足够证据层时才重新评估"
    }
  ]
}
```

## 9. 最小验证顺序

1. 固化 v17 的唯一 owner 和审计投影契约，先用现有真实记录做只读投影样例。
2. 为 `DataQualityGateResult` 建 fixture：新鲜、陈旧、未来时间、quote/bar 冲突、必需字段缺失、可选来源失败。
3. 使用 `002979.sz` 雷赛智能历史快照回放：重复 observation 不推进；第二条 observation 验证第一条；Bridge 断开仍产生本地结果。
4. 把现有预测评估与风险模式 outcome 做统一只读统计，先报告 base rate 和样本量，不发布概率。
5. feature schema 与 `outcomeDefinitionVersion` 同时冻结后可并行做单模式、只读、不落库的相似窗口 PoC；通过无泄漏和基线增益门后，仍先进入生产 shadow，不进入通知。

## 10. 提交给审核 agent 的问题

### Challenge

1. 本文是否把报告中的检索结论误当成完备证明？
2. 数据质量门、run manifest、校准、相似窗口是否与 v17 现有 owner 重复或制造第二套状态？
3. 是否存在目标泄漏、时间穿越、标签主观化、许可证或数据源合规风险未被识别？
4. “不做 hard veto、不把 confidence 当概率、相似窗口延期”是否有代码或产品事实反证？
5. 哪些结论必须降级为待补证？

### Optimize

1. 在不增加 DeepSeek 请求、不扩大账号风险的约束下，采用顺序是否最小且收益最高？
2. run manifest 是否过度设计，能否复用当前表字段或统一 envelope？
3. outcome ledger 应如何复用现有 `StockWatchPredictionService` 和 v17 active tests，避免重复 owner？
4. 相似窗口是否应前移做极小离线实验，或应完全推迟？请给出明确发布门。
5. 哪些建议应进入正式 v17，哪些只应保留在 research backlog？

## 11. 独立审核结果与主 agent 裁决

| 审核 | Agent | Verdict | 核心意见 | 主 agent 裁决 |
|---|---|---|---|---|
| Challenge | `Erdos` / `019f6a77-dba7-7b51-9f89-be7ab25eeb84` | 最终 `PASS`（4 轮差异复核） | C1/C2/C4 降级；明确通知边界、唯一 owner、manifest 脱敏；冻结 outcome 定义；SignalForge stub 不是生产 fallback | 全部采纳，最终无阻断 |
| Optimize | `Zeno` / `019f6a77-dc8f-7ef2-923e-f5aa95bf4012` | 最终 `PASS`（3 轮差异复核） | manifest 改派生投影；Prediction 保持 forecast 专用；补 capability 矩阵；相似窗口可前移极小 PoC；当前 schema repair 违反单请求 | 全部采纳，最终无阻断 |

逐 claim 最终裁决：

- `C1`：部分属实。新 L0/数据质量/tests 采用软解释边界，模型 finding 不能单独打开通知门；存量 GENERAL 模型规则仅作为显式 `LEGACY_MODEL_RULE` 兼容例外，不能伪装成确定性事实。
- `C2`：部分属实。需要统一审计契约，但拒绝新增独立 manifest 状态机，改为跨唯一 owner 的派生只读投影。
- `C3`：属实。当前 confidence/probability 未校准。
- `C4`：部分属实。生产采用延期；极小离线 PoC 可在 feature schema 与 `outcomeDefinitionVersion` 同时冻结后前移。未检查运行数据库或外部资产，不能断言完全没有历史数据集。
- `C5`：属实。报告不能推出当前项目应实现订单 hard veto 或操纵事实认定。

最终判定：**部分采纳报告，保留 v17 主架构；本文已锁定 owner、capability 数据门、模型通知边界、审计脱敏和真正单请求，正式 v17 应原样继承这些发布门。**

进入正式 v17：修订后的 `A01-A06`、条件化 `A12`、新增阻断门 `A13`。

进入 research backlog：`A07-A11`；其中 A07/A08 只允许前移不落库、不调模型的极小离线 PoC。

明确拒绝：`N01-N05`、独立 manifest 状态机、第二套通用 outcome ledger。
