# 股票盯盘 v17 首轮真实烟测审计包

日期：2026-07-16

状态：待独立 Challenge 与 Optimize 审核。本文只记录首轮实现和烟测证据，不宣称完成。

## 目标与约束

- 主目标：在尽量少调用 DeepSeek 网页账号的前提下，让异动判断复用技术分析事实，并持续验证历史主力意图。
- 次目标：同一 observation/generation 最多一次不可逆模型提交；本地 R01-R04 不依赖模型；失败可追踪且不静默丢记录。
- 约束：保留 `GENERAL_LEGACY` 兼容；不把 confidence 冒充涨跌概率；真实烟测使用 `002979.sz`；首轮问题必须先统一审核再改业务代码。

## Claim Graph

```json
{
  "objective": {
    "primary": "低封号风险下的连续主力意图验证",
    "secondary": ["单次模型提交", "确定性风险闸门", "可追踪失败"]
  },
  "constraints": [
    "保留 GENERAL_LEGACY 兼容",
    "真实股票使用 002979.sz",
    "审核前不修首轮业务问题"
  ],
  "claims": [
    {
      "id": "S1",
      "statement": "v17 的单元和 H2 持久化机制在测试边界内成立",
      "type": "fact",
      "dependsOn": ["E1", "E2"],
      "supportingEvidence": ["E1", "E2"],
      "hiddenAssumptions": ["测试数据覆盖真实 symbol/market 编码方式"],
      "falsifiers": ["真实 API 路径使用了测试未覆盖的 stock key 编码"],
      "alternativesConsidered": [],
      "confidence": "high"
    },
    {
      "id": "S2",
      "statement": "真实 monitor 路径存在 stock key 分隔符错误，导致 A 股 L0 和事实落库失效",
      "type": "fact",
      "dependsOn": ["E3", "E4", "E5"],
      "supportingEvidence": ["E3", "E4", "E5"],
      "hiddenAssumptions": [],
      "falsifiers": ["证明 stockKey 返回冒号格式，或 buildRiskPatterns 不依赖字符串拆分"],
      "alternativesConsidered": [
        {"id": "A1", "summary": "把 split 改成点号", "rejectedBecause": "仍保留脆弱字符串协议"},
        {"id": "A2", "summary": "用 StockSymbol/StockRef 作为 map key", "rejectedBecause": "待 Optimize 比较改动收益"}
      ],
      "confidence": "high"
    },
    {
      "id": "S3",
      "statement": "真实技术分析未调用模型，失败于 Eastmoney 日 K 前置依赖，并且没有留下本次事实、任务或失败分析记录",
      "type": "fact",
      "dependsOn": ["E6", "E7", "E8"],
      "supportingEvidence": ["E6", "E7", "E8"],
      "hiddenAssumptions": ["首轮前后计数足以证明本次没有新增记录"],
      "falsifiers": ["找到对应 submission task、fact observation 或本次 ERROR analysis 行"],
      "alternativesConsidered": [
        {"id": "A3", "summary": "外部数据失败仍先持久化 canonical quote/L0 skipped fact 与 DATA_UNAVAILABLE 记录", "rejectedBecause": "待审核"},
        {"id": "A4", "summary": "使用带质量闸门的上一成功快照 daily bars 作为降级基线", "rejectedBecause": "需审核时效和一致性边界"}
      ],
      "confidence": "high"
    },
    {
      "id": "S4",
      "statement": "新规则不调用模型的目标基本实现，但当前仍可能为确定性规则构建并拉取完整 AI context",
      "type": "inference",
      "dependsOn": ["E9"],
      "supportingEvidence": ["E9"],
      "hiddenAssumptions": ["多数主力意图条件会命中 enhanced context 关键词"],
      "falsifiers": ["证明非 legacy 路径不会进入 buildAiContext 的外部数据分支"],
      "alternativesConsidered": [
        {"id": "A5", "summary": "非 legacy 只组装已计算的 riskPattern 与历史摘要，不再调用 buildBundle", "rejectedBecause": "待审核"}
      ],
      "confidence": "medium"
    },
    {
      "id": "S5",
      "statement": "提交回执能阻止重复 provider 调用，但完成状态和业务分析落库之间仍存在一致性窗口",
      "type": "inference",
      "dependsOn": ["E10", "E11"],
      "supportingEvidence": ["E10", "E11"],
      "hiddenAssumptions": ["analysis insert 或 prediction insert 可能独立失败"],
      "falsifiers": ["证明 task success、analysis insert 和 prediction insert 位于同一事务且原子提交"],
      "alternativesConsidered": [
        {"id": "A6", "summary": "先保存 provider response，再在独立可恢复投影事务中写 analysis/prediction，最后置 PROJECTED_SUCCESS", "rejectedBecause": "待 Optimize 评估复杂度"}
      ],
      "confidence": "medium"
    },
    {
      "id": "S6",
      "statement": "当前不能宣称真实链路验证通过，必须先修 S2，并决定 S3-S5 的最小发布边界",
      "type": "recommendation",
      "dependsOn": ["S1", "S2", "S3", "S4", "S5"],
      "supportingEvidence": ["E1", "E3", "E6", "E9", "E10"],
      "hiddenAssumptions": [],
      "falsifiers": ["完整真实链路产生 fact、test verdict、单一 task receipt 和 SUCCESS analysis"],
      "alternativesConsidered": [],
      "confidence": "high"
    }
  ],
  "conclusion": {"dependsOn": ["S2", "S3", "S6"]}
}
```

## 代码/事实证据

- `E1` 直接证据：`mvn -q test` 退出码 0；Surefire 汇总 109 tests、0 failures、0 errors、0 skipped。
- `E2` 直接证据：`target/surefire-reports/com.aimeeting.room.StockWatchV17PersistenceSmokeTest.txt` 为 2 tests、0 failures，覆盖 observation 去重、下一 observation 证伪和 invalid response receipt 去重。
- `E3` 直接证据：`StockWatchMonitorService.stockKey()` 在 `src/main/java/com/aimeeting/room/service/StockWatchMonitorService.java:2428` 生成 `symbol.market`，但 `buildRiskPatterns()` 和 `persistRiskFacts()` 分别在同文件 `:738`、`:807` 用 `split(":", 2)`。
- `E4` 直接证据：真实 monitor 响应 `/tmp/aimeeting-v17-smoke/monitor-response.json` 返回 `symbol=002979.sz, market=""`、`market_not_supported_for_l0`，fact receipt 为 `canonical_quote_timestamp_missing`。
- `E5` 直接证据：同一响应中的真实 quote 为雷赛智能 002979.sz，`timestamp=1784187285`，所以 timestamp missing 是 market 提前返回后的连锁结果，不是行情没时间戳。
- `E6` 直接证据：真实 `POST /api/stock-watch/technical-analysis/run` 返回 HTTP 500；日志 `/Users/wangzilong/.aimeeting-room/logs/backend.launch.log:29336-29340` 显示 `dailyKline unexpected end of stream` 与 `need_at_least_30_bars`。
- `E7` 直接证据：对完全相同的 Eastmoney Kline URL 独立 curl 三次均为 HTTP 000/Empty reply，错误文件位于 `/tmp/aimeeting-v17-smoke/eastmoney-kline-{1,2,3}.json.err`。
- `E8` 直接证据：真实调用前后 `002979.sz` 的 v17 表计数均为 fact=0、task=0；技术分析总数仍为 21。代码在 `StockWatchTechnicalAnalysisService.java:339-342` 先因 required data 抛错，`riskPattern/fact/task/ERROR record` 均位于后面。
- `E9` 直接证据：`StockWatchMonitorService.java:369` 对所有 AI 规则先调用 `buildAiContext()`，到 `:370-372` 才区分 legacy model 与 deterministic；enhanced context 会调用 `marketDataService.buildBundle()`。
- `E10` 直接证据：`StockWatchAnalysisTaskService` 和 mapper 使用唯一 submission key 预占，`markSubmitted` 有 version/status 条件；H2 测试验证相同 key 不二次提交。
- `E11` 直接证据：`StockWatchTechnicalAnalysisService.java:434-458` 先 `markSuccess`，后插入 analysis 与 predictions；`StockWatchAnalysisTaskMapper.xml:49-55` 的 complete 无 expected version/status 条件，service 也不检查 update count。
- `E12` 直接证据：运行时技术分析 status 明确返回 `bridgeUrl=http://127.0.0.1:8791/search`；真实失败后 bridge health 仍 `busy=false`，且 task 表无记录，支持“模型未调用”。
- `E13` 直接证据：LaunchAgent 原把异动规则指向 8791、技术分析指向 8789，已仅在本机运行配置中统一成 8791/9344；这是环境编排修正，不属于本轮业务代码修复。

## 首轮问题清单

| ID | 分类 | 当前判断 | 影响 |
| --- | --- | --- | --- |
| F1 | 已证实 bug | stock key 使用点号，消费端按冒号拆分 | 所有 L0 新规则把 A 股当不支持，事实不落库 |
| F2 | 已证实外部依赖失败 + 设计缺口 | 日 K 连续空响应；前置 hard fail | 不调用模型，但 API 500 且本轮无事实/失败记录 |
| F3 | 高风险设计缺陷 | deterministic 前仍构建 AI context | 可能产生无价值的数据请求和调度耗时 |
| F4 | 高风险一致性缺口 | task SUCCESS 早于 analysis/prediction 投影 | 可能出现 provider 成功但页面无分析 |
| F5 | 待审核优化 | model intent tests 的 requiredObserved=1 | 可能一次观察就把单指标测试标成 SUPPORTED，是否过早收敛待定 |
| F6 | 已解决运行问题 | KeepAlive LaunchAgent 与手工进程抢端口、账号配置分裂 | 已统一专用股票账号并由 LaunchAgent 托管 |

## 设计边界

- 不能因 F2 自动再提交模型；数据不完整时必须在 provider 前结束。
- 修复 F1 后仍需保持 canonical quote 只取一次，R01-R04 只由本地代码计算。
- 外部日 K 降级若采用缓存，只能使用有来源日期、哈希和最大陈旧度的已成功快照；不得把旧数据伪装成实时。
- `GENERAL_LEGACY` 可继续调用模型；`L0_ADVISORY` 与 `RISK_PATTERN_REQUIRED` 不应调用模型做异动触发。
- 技术分析的一次模型响应可以同时生成技术结论和连续主力意图；不再为异动规则单独开网页对话。

## 推理图说明

`109 绿测 -> 机制在测试边界成立`，但 `真实 002979 monitor -> key 拆错 -> L0/事实失效`，且 `真实 technical -> Eastmoney 日 K 失败 -> provider 前 500 -> 无任何新记录`。因此当前只有单次提交和本地持久化组件得到局部证明，完整生产链不通过。

可推翻当前结论的最小反证是：修复 F1 后，以真实 002979 quote 产生 `market=sz` 的 fact observation；再通过受控 daily-bar 来源完成一次且仅一次 8791 provider 调用，得到 task receipt、SUCCESS analysis、mainForceIntent tests，并由第二 observation 生成旧 test verdict。

## 审核问题

Challenge 必须独立核对 E3-E12，并攻击 S1-S6。Optimize 必须回答：

1. F1 应做最小分隔符修复，还是消除字符串 key 协议？
2. F2 最小可靠方案是“只记录失败”，还是“缓存 daily bars 降级 + 明示质量”？
3. F3-F4 是否应阻塞本轮发布？若阻塞，最小可维护改法是什么？
4. F5 是否违背“每次观察缩小验证范围”的目标，还是可作为第一版单步测试接受？
