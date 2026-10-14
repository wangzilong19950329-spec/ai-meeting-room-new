# 持仓风险助手实施清单

本轮只设计，所有实施项未完成。本清单与 [原79项修复](../improve-stock-watch-decision-reliability/tasks.md) 均须做；跨线排序见 [统一路线](../../../docs/design/stock-watch-unified-delivery-plan-2026-10-05.md)。不授权自动交易、外部通知、购买数据或新增模型调用；启用通知须按明确policy与通道选择执行。

## 1. N0 支持范围与数据合同

- [ ] 1.1 冻结HEAD、逐文件dirty基线和部署schema/reader版本，确认与原M0共享但不覆盖其证据。
- [ ] 1.2 核验首期instrument profile、交易日历、可卖与撮合时段；不支持标的列显式原因。
- [ ] 1.3 在真实交易窗口只读采样quote源身份、endpoint、source年龄和source-clock质量，决定30秒采集与freshness是否可行。
- [ ] 1.4 核验分钟条时间语义、OHLC、完成证据、缺口和price basis；确认信号后路径能否支持正式结果。
- [ ] 1.5 核验volume/amount/flow字段单位、累计/区间、日期清零和修订行为，未知feature禁用。
- [ ] 1.6 冻结用户持仓录入/保护参数/通知覆盖与预算合同；无授权不发测试外部通知。
- [ ] 1.7 完成N0证据审核，按报价保护/区间特征/完整结果路径分别确认支持子集、SLO和remaining boundaries；先选择常驻页面或离线通知路径，不用HTTP成功代替实时性。

## 2. N1 持仓簿与权限

- [ ] 2.1 定义Position/Lot/Event DTO、BigDecimal数值、owner、generation、版本和来源合同。
- [ ] 2.2 创建position/lot/event Mapper、索引、additive upgrade/readiness，同步initializer与两份SQL。
- [ ] 2.3 实现草稿登记、显式激活和holding-group导入；不填猜测数量/买入日期。
- [ ] 2.4 实现批次可卖估计/用户确认快照、未知/同日新买/企业行动处理。
- [ ] 2.5 实现clientEventId幂等、部分卖批次分配、原子余额/CAS、correction与policy版本。
- [ ] 2.6 实现本人positions/events API，按服务端currentUserId校验每个id，风险端点只用header认证。
- [ ] 2.7 新增持仓录入/批次/保护设置工作台；ACK/计划退出与登记成交分开。
- [ ] 2.8 验证跨用户、并发/负余额/重试、清仓重买、缺成本、H2/MySQL升级及真实认证API/UI。

## 3. N2 快速观测与确定性影子

- [ ] 3.1 在既有quote网络边界提供新RiskObservationEnvelope与严格身份/源时间/尺度质量adapter，保持旧Map消费者兼容。
- [ ] 3.2 新增风险市场观察/assessment/episode存储、lease/checkpoint与schema gate；个人数据不进canonical hash。
- [ ] 3.3 实现有界批量捕获、标的公平/限流/退避/断路与日线缓存，丢过期tick但保留MISSED机会。
- [ ] 3.4 实现保护高水位；区间资格合格后实现交易分钟窗口、累计delta/可比量能，未知feature不补0，不阻断合格报价保护。
- [ ] 3.5 实现P01-P03纯评估与解释引用，用户空阈值不猜、首触不等模型、启用前high不进trail。
- [ ] 3.6 实现冻结assessment、CAS episode首警/升级/恢复/过期/清仓；缺数据不变安全。
- [ ] 3.7 记录capture/compute/publish分段时点与quality，SHADOW期间关闭新外部通知。
- [ ] 3.8 测试乱序/重复/错市场/缺资金/跨日清零/恢复滞回/跨日持续及模型不可用旁路；前置7.1后，合格候选按7.2并行SHADOW，不等N6批准才开始积累。

## 4. N3 时效性发布与可靠交付

- [ ] 4.1 扩展既有delivery owner的风险领域/owner/position generation/transition/expiry/quality与索引，legacy默认兼容。
- [ ] 4.2 实现发布事务position锁/版本与generation复核、episode CAS及assessment/transition/publish/opt-in obligation原子落库；过时输入不发布，并发成交/策略修改与重启不丢义务或创建新逻辑警报。
- [ ] 4.3 实现严重升级独立key/绕普通冷却、同级去重、owner通知配置隔离，不继承全局股票收件人。
- [ ] 4.4 实现发送前position/policy/episode/expiry复核、清仓/暂停/改policy取消及在途边界。
- [ ] 4.5 实现确认失败有限重试与transport UNKNOWN，不盲重发、不把Message-ID当exactly-once。
- [ ] 4.6 实现认证transition短轮询/实际显示回执、显式browser/声音permission、ACK；不在URL暴露token。
- [ ] 4.7 新增第一屏风险/源时延/coverage/可卖/通知与动作时间线，保留预测和意图入口，检查窄屏。
- [ ] 4.8 升级并排空旧dispatcher、验证领域过滤兼容reader/fence，再做受控transport与多实例/过期/取消测试。
- [ ] 4.9 在用户明确policy/通道后完成真实有效窗口首警和升级链验收；登记测试不下真实订单，不声称避损已证。

## 5. N4 原模型回复风险解释旁路

- [ ] 5.1 独立冻结新助手extractor/protocol与正式cutoff，原M5-B词典/协议不被覆盖。
- [ ] 5.2 只消费committed reply/task/receipt，确定性提取强/条件/早期警告及否定/引述/历史/模糊。
- [ ] 5.3 保存短片段/hash/位置/引用、request至extracted时间，迟到/冲突/无警告独立状态。
- [ ] 5.4 实现慢解释只补合格当前episode，过期不覆盖；失败不回滚已有analysis或快路径。
- [ ] 5.5 候选规则/模型在SHADOW或用户明确同意实验模式展示，不升级为可信出货/卖出指令。
- [ ] 5.6 验证模型无新增提交、原v6/v7冻结parser/RETURN_SIGN/意图/V1隔离及未来真实回复采集。

## 6. N5 风险与执行复盘

- [ ] 6.1 定义REMAINDER_SESSION/NEXT_SESSION与完整机会池、去重主事件、缺失/失败/无告警分母；将首次SHADOW前冻结的scheduledAt共同结果协议与实际availableAt信号评价分开，MISSED无伪发布时间。
- [ ] 6.2 复用原M1已合格日历/尺度纯工具，独立实现发布后anchor/完整分钟资格与冻结延迟界限。
- [ ] 6.3 新增risk evaluation version/revision/nextRetry/CAS；终态不可变，源修订只supersedes。
- [ ] 6.4 实现信号后最差收益、阈值风险、迟到/提前量与sampled-risk非完整阴性语义。
- [ ] 6.5 实现可卖批次、撮合时段/无法成交、延迟费用与同量持有基线的EXECUTION_PROXY及错过上涨成本，显式计入两侧相同的剩余锁定仓位。
- [ ] 6.6 实现USER_REPORTED成交比较，缺仓位/成交/成本/窗口不估个人节省金额。
- [ ] 6.7 实现全filter/snapshot/version风险统计与样本定位，按股票/日期/episode相关性报告覆盖与分母。
- [ ] 6.8 补警告前low/部分分钟/停牌/次日跳空/缺anchor/MISSED共同标签/部分可卖同量比较/同股多用户/窗口重叠/数据修订测试。

## 7. N6 前向效果与候选准入

- [ ] 7.1 在首次N2/N4正式候选SHADOW前冻结主事件、同机会基线、用户误报漏报效用、覆盖/延迟/精度及固定解盲规则；不等最后N6才预注册。
- [ ] 7.2 按各自数据资格与协议随N2/N4并行采集C01-C03/模型与简单价格基线，冻结机会/原始证据后等N5计分；不使用未来结果挑控制，不污染V1盲测。
- [ ] 7.3 按真实窗口/日期/个股分组留出和purge，区分早期条件警告与强措辞，纳入失败/迟到/覆盖。
- [ ] 7.4 给出风险增量、提前量、误报/漏报和执行代理机会成本，数据不足不改规则救结果。
- [ ] 7.5 裁决PROSPECTIVE_APPROVED/NO_INCREMENTAL_EDGE/INSUFFICIENT_EVIDENCE，通知启用另外确认。
- [ ] 7.6 主力意图与完整预测持续按各自原任务评分，不因风险候选效果好删除其功能。

## 8. 跨线发布与收尾

- [ ] 8.1 每批对本次实际写集与验收合同做Challenge/Optimize并裁决属实/不属实/待补证。
- [ ] 8.2 公共schema/delivery/UI写集统一合并，单writer完成Java/H2/MySQL/frontend验证并raw最终检查。
- [ ] 8.3 证明listener加载新版本，真实API/UI/transport时点与DB证据一致，业务新鲜度与效果门分开。
- [ ] 8.4 演练OFF/SHADOW/LIVE_PROTECTION、域reader兼容回滚/新writer停止、原链继续和UNKNOWN不重发。
- [ ] 8.5 逐批记录shadow-fix证据与边界，只提交已完成白名单；本轮设计没有实现项可标完成。
