<claude-mem-context>
# Memory Context

# [ai-meeting-room-new] recent context, 2026-07-27 10:20am GMT+8

Legend: 🎯session 🔴bugfix 🟣feature 🔄refactor ✅change 🔵discovery ⚖️decision 🚨security_alert 🔐security_note
Format: ID TIME TYPE TITLE
Fetch details: get_observations([IDs]) | Search: mem-search skill

Stats: 3 obs (153t read) | 50,432t work | 100% savings

### Jul 22, 2026
S119 执行测试并分析 Surefire 报告 (Jul 22 at 11:31 PM)
### Jul 26, 2026
2565 11:23p 🔵 确认后端服务运行状态
2566 " 🔵 前端服务未启动
2567 " 🔵 前端服务运行状态确认
**已探索**: 执行了测试命令以验证功能，并观察到控制台输出因长度限制被截断。

**已了解**: 控制台直接输出的测试结果在规模较大时会被截断，无法获取完整的测试计数。

**已完成**: 成功触发了测试执行，并确定了需要通过解析 Surefire 报告文件来获取精确的测试统计数据。

**下一步**: 定位并读取 Surefire 生成的 XML 或文本报告文件，从中提取测试通过/失败的精确计数。


Access 50k tokens of past work via get_observations([IDs]) or mem-search skill.
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
