# Evidence Chain（计划全生命周期不可变证据链）

在现有 Spring Boot 3 + PostgreSQL 架构内，为计划状态变化、确认记录、模拟回执、
拒绝结果和销记操作建立可验证的哈希链式证据。**不依赖区块链或任何外部可信服务**，
信任锚仅为 PostgreSQL 自身的事务与权限体系。

## 1. 核心模型

### 事件表 `plan_evidence_event`（仅追加）

| 列 | 含义 |
|---|---|
| `plan_id, seq` | 每个计划内严格递增的序号（唯一约束） |
| `event_type` | `CREATE / ANCHOR / START / CONFIRMATION / MOCK_RECEIPT / REJECTION / WRITEOFF` |
| `from_status, to_status` | 本次状态迁移 |
| `prev_hash` | 上一事件 `event_hash`；首事件为 64 个 `0`（创世锚） |
| `payload_json` | 规范化（canonical）JSON 业务载荷 |
| `payload_hash` | `sha256(payload_json)` |
| `event_hash` | 见下方哈希公式 |
| `business_record_id` | 对应确认/回执/拒绝/销记业务行 ID，用于双向核对 |
| `idempotency_key` | 命令幂等键，`(plan_id, idempotency_key)` 唯一 |
| `anchor` | 是否为历史锚点事件 |

哈希公式（字段以换行拼接）：

```
event_hash = sha256(seq | planId | eventType | fromStatus | toStatus
                    | prevHash | eventTime(UTC,ms) | payloadHash)
```

业务表：`plan`、`plan_confirmation`、`plan_mock_receipt`、`plan_rejection`、
`plan_writeoff`；历史暂存表 `legacy_plan_snapshot`。

### 数据库级防护（V2 迁移）

`plan_evidence_event` 上有 `BEFORE UPDATE/DELETE` 触发器，普通会话禁止修改/删除，
使表在数据库层面就是仅追加的（需要 DBA 权限和会话开关才能绕过）。

## 2. 事务与并发（杜绝半完成记录）

每个写操作在**同一个数据库事务**内完成：

1. `SELECT ... FROM plan WHERE id=? FOR UPDATE`（锁定计划行）；
2. `SELECT ... FROM plan_evidence_event ... ORDER BY seq DESC LIMIT 1 FOR UPDATE`
   （锁定链尾）；
3. 幂等检查 → 状态机校验 → 插入业务行 → 从锁定的链尾推导 `seq/prev_hash` 并
   插入证据事件 → 更新 `plan.status`；
4. 提交。任一步失败整体回滚，不会出现“有状态无证据”或“有证据无状态”。

并发命令在计划行锁上串行化；序号间隙、重哈希、跨表计数、业务行存在性、
计划状态等于链尾等全部由校验接口复核。

## 3. 幂等

所有命令必须携带 `idempotencyKey`。

- 同一 `(planId, key)` 重放：直接返回首次结果（原事件哈希、原业务行 ID，
  `replayed=true`），**不插入业务行，不新增事件，不改状态**。
- 同一 key 用于不同动作：返回 `409 IDEMPOTENCY_CONFLICT`。
- 事务回滚后用相同 key 重试：正常成功且只产生一次结果。

## 4. 历史数据安全初始化（锚点）

启动时（或 `POST /api/plans/anchor-now`）`AnchorMigrationService`：

- 对**已存在于 `plan` 表但没有任何事件**的历史计划，逐计划独立事务追加一个
  `ANCHOR` 事件（`from_status=NULL, to_status=历史状态`，载荷含原 plan_no、
  标题、状态、创建/更新时间快照）；
- 对 `legacy_plan_snapshot` 暂存行：保留原始数据导入 `plan`（保留原状态和
  创建时间），同事务追加锚点并回写 `anchor_event_id`；
- **原记录的 id、编号、状态、时间戳均不改动、不删除**；
- 可任意重复执行：已有事件的计划跳过、锚点自身的唯一键兜底，重启后绝不重复锚定。

锚点之后的业务事件正常挂接在锚点之后（如 `CONFIRMED → RECEIPTED → WRITTEN_OFF`）。

## 5. 校验与复盘

- 复盘查询：`GET /api/plans/{id}/evidence` —— 返回按 seq 排列的完整证据链。
- 校验：`GET /api/plans/{id}/verify` —— 从头重算每条哈希与状态链，发现问题立即
  停止并返回**首个断链点** `firstBrokenSeq` 与原因码：

  `SEQ_GAP` / `PREV_HASH_MISMATCH` / `PAYLOAD_HASH_MISMATCH` /
  `PAYLOAD_NOT_CANONICAL` / `PAYLOAD_UNPARSEABLE` / `EVENT_HASH_MISMATCH` /
  `INVALID_FIRST_EVENT` / `STATUS_CHAIN_BROKEN` / `ILLEGAL_TRANSITION` /
  `BUSINESS_RECORD_MISSING` / `TAIL_STATUS_MISMATCH` /
  `BUSINESS_COUNT_MISMATCH` / `EMPTY_CHAIN`。

载荷篡改即使同时重算 `payload_hash` 也会因 canonical JSON 形态变化被发现。

## 6. HTTP API

```
POST   /api/plans                         创建计划（CREATE 事件，事务内）
POST   /api/plans/{id}/actions            状态动作：START / CONFIRMATION /
                                          MOCK_RECEIPT / REJECTION / WRITEOFF
GET    /api/plans                         列出计划
GET    /api/plans/{id}/evidence           复盘：完整证据链
GET    /api/plans/{id}/verify             校验：首个断链点定位
POST   /api/plans/legacy-snapshots        录入一条历史暂存数据（管理/迁移用）
POST   /api/plans/anchor-now              手动触发幂等锚定
GET    /api/plans/legacy-snapshots        查看暂存与锚定结果
```

请求示例：

```json
POST /api/plans
{"planNo":"P-2026-01","title":"年度检修","operator":"alice","idempotencyKey":"create-1"}

POST /api/plans/1/actions
{"action":"CONFIRMATION","operator":"bob","remark":"现场确认","idempotencyKey":"cfm-1"}

POST /api/plans/1/actions
{"action":"MOCK_RECEIPT","channel":"ERP","receiptPayload":"{\"code\":\"OK\"}",
 "operator":"system","idempotencyKey":"rcp-1"}

POST /api/plans/1/actions
{"action":"WRITEOFF","operator":"carol","remark":"销记完成","idempotencyKey":"wo-1"}
```

## 7. 运行

```bash
# 需要 JDK 17、Maven 3.9+、PostgreSQL
createdb evidencechain
mvn spring-boot:run

# 集成测试使用 zonky embedded-postgres（真实 PG，无需外部数据库）
mvn test
```

## 8. 验收测试对照

| 验收点 | 测试类 |
|---|---|
| 完整开工—销记链可校验 | `FullLifecycleChainTest#fullStartToWriteoffChainVerifies` |
| 重复请求不新增事件/业务结果 | `FullLifecycleChainTest#duplicateRequestsAreIdempotentAndProduceNoNewEventsOrRecords` |
| 同键不同动作拒绝 | `FullLifecycleChainTest#sameKeyUsedForDifferentActionIsRejected` |
| 篡改哈希/指针/载荷/删事件/改状态/删业务行并定位断链 | `TamperDetectionTest` |
| 历史计划迁移后可查询可验证、重跑不重复 | `HistoricalMigrationTest` |
| 事务失败回滚（无半完成记录）+ 失败后重试 | `TransactionAtomicityTest#failureAfterWritesRollsBackEverything` |
| 并发（重启前后交错语义）不破坏序列 | `TransactionAtomicityTest#concurrentCommandsOnSamePlanNeverCorruptTheSequence` |
| 拒绝结果事件与终态 | `RejectionChainTest` |
