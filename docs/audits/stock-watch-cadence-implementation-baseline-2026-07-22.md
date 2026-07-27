# 个股分析频率实施前工作树基线

冻结时间：2026-07-22  
用途：只用于证明本轮实施是在当前用户工作树上增量合并；不得用这些哈希覆盖或恢复文件。

| 文件 | 实施前状态 | SHA-256 |
| --- | --- | --- |
| `frontend/src/views/StockWatch.vue` | modified | `0d6e9ca5c52daa2491b8d31320c89f6a14fba226e753ebda1963e7eef2685cd3` |
| `src/main/java/com/aimeeting/room/config/StockWatchTechnicalSchemaInitializer.java` | modified | `668f14336c0a01973d73249f58be42360c68db5a821f7276b9d404a522b27abc` |
| `src/main/java/com/aimeeting/room/service/StockWatchTechnicalAnalysisService.java` | modified | `fb26a104a8b812ed1246be219b7b00f79fa0d429bcab9584694fc21ac6e9b47d` |
| `src/main/java/com/aimeeting/room/service/StockWatchMonitorService.java` | modified | `9988754c5cb6f95e2ed373223cd2f637a655eae81a8c424c5ff09f27b0a79462` |
| `src/main/resources/sql/init.sql` | modified | `f035cd3f925c3ee820ccc2873dd1b1ea78b18598b250faf51f5c450b7d38cb13` |
| `src/main/resources/sql/schema-local.sql` | modified | `de572bd07e62f51fed34ef44117f60d293274108e49f1a564fa38c066bc21200` |
| `src/main/java/com/aimeeting/room/entity/StockWatchAnalysisDemand.java` | untracked | `5a817e5863ca0d40d88c17153fcee8c6e1c01fc52e290557c6441d0a5099e2ff` |
| `src/main/java/com/aimeeting/room/service/StockWatchAnalysisDemandService.java` | untracked | `7c777bcf6202dec6c458b50678aee1d62197ec5db73cac1fd6120163eb8cd649` |
| `src/main/java/com/aimeeting/room/dao/StockWatchAnalysisDemandMapper.java` | untracked | `16a36b64229c8ca4165ad93c424fb106a40bc532768acabc25d45ffd4b3ffca0` |
| `src/main/resources/mapper/StockWatchAnalysisDemandMapper.xml` | untracked | `444191012d5e0470c8dbcf2df511c198c5531513e962dea46609657bec15a321` |

保护规则：

1. 不执行 reset/checkout/覆盖恢复。
2. 重叠文件只用小范围 `apply_patch` 增量编辑。
3. 最终逐文件检查 diff，明确区分本轮 cadence 改动和既有 v17 改动。
4. 四个实施前已 untracked 的 demand 文件无法在 Git 提交中天然拆分既有内容与本轮增量；除非能精确重建边界，否则不得把整个文件冒充为本轮独立提交。
