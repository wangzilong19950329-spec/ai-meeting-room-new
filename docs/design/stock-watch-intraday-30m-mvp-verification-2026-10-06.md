# M30 MVP 实施验证

日期：2026-10-06。范围：同次回复扩展、原分析详情一行、只读复盘。完整持仓助手及原79项修复未实施。

## 已完成

- 新 canonical DAY 请求才按显式试点配置追加 M30，冻结 asOfAt/targetAt、报价参考及120秒/300秒预算；旧任务不升级、不补调。M30 不进入原 forecasts 数组，不占用v7。
- M30 缺失、拒答、非法、输入不合格、入库迟到及到期独立展示。公开接口仅投影数字/枚举/固定时间，不开放任务 envelope 或 v6 rawResponse。非法可选扩展不修改普通成功状态；整份 JSON 损坏仍走原失败逻辑。
- 新增报价供应商身份/端点/未复权标记。聚焦测试确认新增元数据不改变 canonical fact snapshot；未修改 fact allowlist、身份键、调度或表结构。
- 本地 JSON/CSV 只读研究脚本：完整已请求任务分母、原始回复、请求摘要验证、固定合格端点、拒答/缺失/失败、同样本基线；新目录独占写入，拒绝覆盖已有快照，不写业务库。

## 验证回执

- Java：`mvn -q -Dtest=StockWatchIntradayForecastTest,StockWatchTechnicalAnalysisServiceTest,StockWatchAnalysisTaskServiceTest,StockWatchAnalysisProjectionServiceTest,StockWatchPredictionServiceTest,StockWatchFactPersistenceServiceTest test`。多次执行均通过；最终 Surefire 六份报告共88项，失败/错误/跳过均0，其中M30新增9项。
- 前端：`node --test frontend/src/modules/stock-watch/runtime/*.test.mjs`，7项通过（M30新增4项）；`npm run build` 通过，有现有打包体积/CJS提示，无编译错误。
- 脚本：`python3 -B -m unittest discover -s scripts -p test_review_stock_watch_m30.py -v`，29项通过，无网络/模型/DB写入。
- 浏览器：独立受控组件样例，1280x800及375x812截图；五类状态可见，手机行无溢出/相互覆盖；11秒观察证明倒计时会变“已到期”。这是组件测试，不是登录后真实股票页面闭环。[桌面截图](m30-mvp-verification/desktop.png)、[手机截图](m30-mvp-verification/mobile.png)。样例已卸载。
- 数据库：通过受保护本地环境凭据连接，`START TRANSACTION READ ONLY`，执行脚本的SELECT并rollback。全库M30请求数0、执行中任务0、成功分析1625条（最新id1997）。最新任务创建日2026-08-14导出125条，全部无M30 profile，排除125、不可读envelope为0、M30分母0。[真实只读报告](m30-mvp-verification/db-latest-2026-08-14/report.json)。2026-10-05空请求日也能正常生成零样本报告，准确率为空而非0/100%。
- 后端：原8127服务已重启，进程50531于00:29:43成功启动并监听；3009前端保留。真实技术分析API返回“未登录”，未绕过认证；未认证读取不能证明新投影的运行结果。未调用POST分析/模型或通知。
- 当前索引已更新；手动复核新增 prepare 分支、请求冻结、旧成功校验、详情消费、Mapper现有关联及脚本最终文本。`git diff --check` 对本轮修改的既有文件通过，未回退其他dirty修改。

## 审查与剩余边界

Challenge 的两项有效反证已采纳：联网可能看到asOfAt之后的信息；报价最多早120秒。实现明确 `UNVERIFIED_WEB_SEARCH`、`FROZEN_QUOTE_TO_TARGET`、`DESCRIPTIVE_ONLY`，严格未来30分钟准确率恒为null/NOT_ESTABLISHED。DB_INSERT_PROXY仅是入库代理，不是实际送达。未验证分钟端点则UNSCORABLE，不猜测终价。

Challenge 的源代码复核未发现M30可选字段破坏普通成功链的范围阻塞；没有独立复跑主代理测试，因此不能把静态审核说成独立实施认证。普通定时需求没有targetFact，但可与异常需求合并；后者缺供应商身份元数据时弃权。

Optimize 建议保留当前小范围，并先完成一个标的、一次原计划新任务的真实窗口闭环，不加调度、不补模型调用。已采纳为下一步，而非已完成。

## 启用与首个窗口

默认关闭，试点标的等待用户确认。配置 `STOCK_WATCH_M30_ENABLED=true` 和 `STOCK_WATCH_M30_SYMBOLS=代码.市场` 后正常重启；状态接口提供m30Enabled/m30PilotSymbols。最多1-3只普通沪深股票，只影响新的DAY任务。

首轮先用1只股票，等待原计划任务完成，登录原分析详情确认M30，保存经核验的未复权分钟端点及抓取时点，再运行 `scripts/review-stock-watch-m30.py --date YYYY-MM-DD --mysql-defaults-file <受保护配置文件> --bars-json <合格证据JSON> --output <新目录>`。当前CLI仅导入端点证据，不自动采集；不能把普通VALID分钟结果改个标记就视为已核验。

真实新模型输出、完整登录API/UI链、合格市场端点、实际送达时延和预测增量均尚未验证。开关未启用，所以当前页面没有真实M30新记录。本轮只交付代码与控制验证，不宣称可实盘避损。
