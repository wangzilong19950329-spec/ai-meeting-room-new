<claude-mem-context>
# Memory Context

# [ai-meeting-room-new] recent context, 2026-08-09 10:13am GMT+8

Legend: 🎯session 🔴bugfix 🟣feature 🔄refactor ✅change 🔵discovery ⚖️decision 🚨security_alert 🔐security_note
Format: ID TIME TYPE TITLE
Fetch details: get_observations([IDs]) | Search: mem-search skill

Stats: 3 obs (303t read) | 52,119t work | 99% savings

### Aug 9, 2026
6491 9:32a 🔵 Codex CLI 执行环境配置确认
6511 9:35a 🔵 股票分析会话提示词增量机制审计
S153 审计 AI 会议室项目中关于对话上下文重用及增量更新的实现逻辑。 (Aug 9 at 9:36 AM)
6535 10:03a ⚖️ 确立自动化审计任务的超时与终止策略
**已探索**: 检查了 `StockWatchTechnicalAnalysisService` 中的 `buildPromptMarketDataProjection` 方法、Mapper 映射配置、单元测试报告以及本地环境的工具链配置（Claude CLI, Codex, 网络环境）。

**已了解**: 确认了系统通过 `buildPromptMarketDataProjection` 实现 FULL/DELTA 模式切换，且在满足基准条件时可实现至少 10% 的序列化提示词压缩。同时发现本地自动化审计工具（Codex）因 SSL 证书链信任问题（UnknownIssuer）导致无法连接后端，Claude CLI 也因缺乏认证凭证无法运行。

**已完成**: 完成了手动代码事实核查，验证了核心逻辑（F1-F5, R1-R3），并确认了审计结论为 ARGUMENT_SUPPORTED。完成了环境工具链的审计与诊断，明确了自动化代理失效的根本原因。

**下一步**: 审计任务已达成目标，结论已通过手动核查确认。无需进一步的自动化审计尝试，准备结束当前审计会话。


Access 52k tokens of past work via get_observations([IDs]) or mem-search skill.
</claude-mem-context>

# Evidence-First Code Investigation

Accuracy and evidence completeness take priority over speed, tool-call count, source-line count, and context size. No numeric file, line, or tool budget may be used as a reason to stop collecting evidence.

1. Choose tools by evidence type: use CodeGraph for symbol relationships and call paths; `ast-grep` for syntax-aware structures; and `rg` plus native reads for exact names, configuration keys, strings, YAML, XML, SQL, JSON, templates, logs, generated artifacts, and relationships that static analysis may miss.
2. For code-understanding tasks in this indexed repository, use exact symbol or file names in the initial CodeGraph query. Treat CodeGraph as navigation and candidate evidence, not proof of completeness. Only displayed source lines count as read; absence from the graph is not evidence of absence. Before relying on relationship or impact results, confirm that the index reflects the current working tree. After relevant edits, sync or recheck the index, or independently verify the affected relationships.
3. Follow a progressive evidence ladder: resolve the unique definition; inspect the target implementation and behavior-relevant class or file context; trace inputs, callers, callees, outputs, side effects, and affected tests; then inspect relevant configuration, API and serialization contracts, Mapper/SQL/schema/migrations, generated code, runtime registration, and external I/O.
4. Match evidence depth to the task. A definition-only lookup may stop after the requested definition is uniquely confirmed. Requests to locate usages, callers, references, implementations, or behavior must continue to the evidence depth required by that request. Explanation and diagnosis tasks must cover behavior-controlling dependencies and contradictory evidence; editing and audit tasks must cover affected invariants, consumers, contracts, failure paths, and verification.
5. Expand automatically when output is truncated, deferred, ambiguous, unresolved, internally inconsistent, unexpectedly reports no callers or tests, or does not explain observed behavior. Also expand for annotations, dependency injection/AOP, transactions, scheduling, concurrency, shared state, state machines, inheritance, reflection, dynamic dispatch, registries/events/plugins, persistence, migrations, external I/O, security, authentication, and public contracts.
6. Read the whole file, or exhaustively inspect all relevant regions, whenever correctness depends on file-wide state, lifecycle, locking, overloads, inner types, static initialization, exception policy, or multiple dispersed paths. File size is never a reason to skip required context.
7. Reuse prior evidence only when the exact range was complete and its content is confirmed unchanged. Re-read after edits, stale or truncated output, different semantic needs, concurrent changes, evidence conflicts, or when exact final text is required.
8. If CodeGraph is incomplete, narrow to exact symbols and use the CLI `node`, `callers`, `callees`, `impact`, or `affected` commands, then fall back to `ast-grep`, exact text search, native reads, and runtime evidence.
9. After editing, inspect the final diff and latest changed source; verify relevant callers, callees, direct and indirect consumers, affected tests, and any configuration, API, serialization, persistence, schema, migration, error-path, concurrency, or security boundary implicated by the change.
10. Stop only when the evidence-sufficiency gate is met: every material truncation has been followed up or explicitly shown to be irrelevant, and no material contradiction, ambiguity, or unresolved dependency remains; behavior-controlling configuration and contracts are checked; normal paths, failure paths, and key invariants are covered as appropriate; verification commands/results and remaining boundaries are reported. Never claim completeness from CodeGraph alone.
