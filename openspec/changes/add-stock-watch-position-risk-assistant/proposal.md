## Why

用户人工决定选股与入场，系统的第一产品目标是对已有持仓尽早识别危险、及时提醒并辅助人工退出，风险既可能在当天兑现，也可能在次日兑现。完整涨跌预测保留为辅助，主力意图研究继续并行；本功能是新增能力，不替代 `improve-stock-watch-decision-reliability` 的任何 bug 修复。

## What Changes

- 新增有用户归属的持仓、买入批次、可卖资格估计、保护策略及人工成交记录；现有 `holding` 分组不能当作真实持仓或可卖数量。
- 新增持仓专用快速观测与确定性评估，不等待 DeepSeek，不把现有 10 分钟扫描简单改成高频全量 AI 扫描。
- 分开用户保护条件、可观察市场风险结构、模型风险解释和完整方向预测；记录来源、可用时点、数据质量、版本及失效条件。
- 新增风险事件的持续、升级、恢复、确认与关闭，以及与持仓版本绑定的可靠通知；冷却不吞掉严重升级，模型失败不阻断确定性提醒。
- 复用现有邮件交付义务与认证，增量支持风险来源、时效、取消和不确定交付；活跃页面先用认证短轮询，浏览器通知需要显式授权，邮件不是已读证明。
- 新增信号后风险、预警提前量、漏报误报、执行代理和人工成交复盘；保留无告警、数据故障和无法评分机会，不能用次日收盘方向代替避险效果。
- 新增独立灰度和回滚门：先影子采集，用户保护条件经工程验收后可单独启用；未经前向效果验收的推断风险不得静默升级成可信卖出信号。

## Capabilities

### New Capabilities

- `stock-watch-position-book`: 用户持仓、批次、保护策略、人工执行记录、并发和隐私。
- `stock-watch-position-risk-observation`: 标的身份、行情时间、分钟完整性、字段口径、快速采集及覆盖状态。
- `stock-watch-position-risk-assessment`: 风险来源、条件与失效、模型旁路、事件持续升级和能力隔离。
- `stock-watch-position-risk-delivery`: 时效性通知、持仓关联、去重升级、取消、不确定交付和用户确认。
- `stock-watch-position-risk-evaluation`: 完整机会池、信号后窗口、风险指标、执行与机会成本、前向准入。

### Modified Capabilities

无已发布 capability 修改。原可信评估 change 的规格仍保持独立；本次只追加目标澄清与统一路线，不删除或完成其实施任务。

## Impact

- 复用 `StockQuoteService`、既有 market-data/fact、risk pattern、canonical task/receipt、`StockWatchAlertDeliveryService`、`NotificationService`、运行状态与 Vue 工作台。
- 新增持仓领域 owner 和风险评估/事件 owner；不建立第二套模型提交账本，不把持仓/研究信息写入 canonical `factHash`。
- 增量影响 position/risk DTO、Mapper、schema readiness、认证 API、持仓工作台和现有邮件交付的兼容分支。所有数据库改变都是后续实施项。
- 不接券商、不自动下单、不购买数据、不默认增加 DeepSeek 调用、不改变 priceFailure V1 冻结规则/解盲门、不回填历史为当时已发布预警。
- 统一交付路线见 [总实施路线](../../../docs/design/stock-watch-unified-delivery-plan-2026-10-05.md)。本轮只交付设计文档、规格、任务与审核记录，不改业务代码、数据库、运行配置或服务。
