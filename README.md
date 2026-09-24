# Evidence Chain — 维护计划哈希证据链

为维护计划的 **状态变化、确认记录、模拟回执、拒绝结果、销记操作** 建立可验证、不可变的证据链。
纯 Spring Boot 3 + PostgreSQL 实现：**不使用区块链、不依赖任何外部可信服务（TSA/HSM/对象存储 WORM 等）**，
信任根是 PostgreSQL 单库的 ACID 事务与每行 SHA-256 链式哈希。

## 1. 它提供什么

| 能力 | 实现 |
|---|---|
| 每计划一条顺序哈希事件链 | `plan_evidence_event`，每计划 `seq` 从 1 连续，`event_hash = SHA256(planId, seq, type, stateBefore, stateAfter, prevHash, createdAt(epochMillis), 规范化payload)`，`prev_hash` 指向上一事件哈希，创世事件为 64 个 `0` |
| 状态变更与证据写入原子完成 | 两者在**同一个 Spring 事务**中提交；失败整体回滚，杜绝“有状态无证据 / 有证据无状态” |
| 幂等重放 | 每个请求带 `Idempotency-Key`（或 body 内 `requestId`），DB 唯一约束 + 请求体指纹；重放返回原结果，不新增业务结果、不新增事件，重启后依然成立；同 key 不同 body 返回 409 |
| 并发安全 | `SELECT ... FOR UPDATE` 锁计划行 + `(plan_id, seq)` 与 `request_id` 唯一约束 |
| 复盘查询 | `GET /api/plans/{id}/evidence` 返回计划快照 + 全量事件（seq、状态前后、载荷、前后哈希、时间） |
| 校验 / 定位首个断链点 | `GET /api/plans/{id}/verify` 逐行重算哈希，返回 `valid / firstBrokenSeq / reasonCode`；`GET /api/plans/verify` 全库巡检 |
| 历史数据安全初始化锚点 | 历史表 `legacy_plan` **只读不改、不删不迁走**；每计划独立事务写一条创世 `ANCHOR` 快照事件；可重复执行（跳过已锚定），崩溃后重跑安全 |
| 模拟篡改可定位 | 内容篡改→`CONTENT_TAMPERED`；哈希被改→`CONTENT_TAMPERED`；删行→`SEQ_GAP`；库外改状态→`STATE_DIVERGED` |

## 2. 生命周期

```
DRAFT ──START──▶ ISSUED ──CONFIRM──▶ CONFIRMED ──RECEIPT──▶ RECEIPTED ──WRITE_OFF──▶ WRITTEN_OFF
                                        └──REJECT──▶ REJECTED
历史计划在任意状态以 ANCHOR 创世事件锚定，之后按状态机继续流转。
```

事件类型：`CREATED`（新计划创世）、`ANCHOR`（历史锚定创世）、`STARTED 开工`、
`CONFIRMED 确认`、`RECEIPTED 模拟回执`（事务内生成确定性回执号 `SIM-{planId}-{seq}`）、
`REJECTED 拒绝`、`WRITTEN_OFF 销记`。

## 3. HTTP API

```
POST /api/plans                              创建计划        header: Idempotency-Key
POST /api/plans/{id}/actions                 状态动作         START|CONFIRM|RECEIPT|REJECT|WRITE_OFF
GET  /api/plans/{id}                         计划快照
GET  /api/plans/{id}/evidence                复盘查询（计划 + 全部证据事件）
GET  /api/plans/{id}/verify                  校验单链，定位首个断链点
GET  /api/plans/verify                       全库巡检
POST /api/plans/legacy/anchor                历史计划安全锚定（幂等）
POST /api/plans/legacy/seed                  （测试支持）写入历史行
```

动作请求体：`{"action":"RECEIPT","payload":{"channel":"mock-gateway"},"requestId":"..."}`
（`requestId` 与 `Idempotency-Key` 二选一，header 优先）。

校验返回示例（篡改时）：
```json
{ "planId": 7, "valid": false, "firstBrokenSeq": 3,
  "reasonCode": "CONTENT_TAMPERED",
  "detail": "stored event_hash does not match hash recomputed from row contents",
  "verifiedEventHashes": ["<hash seq1>", "<hash seq2>"] }
```

测试辅助端点（仅 `evidence.test-endpoints-enabled=true` 时开启，生产默认关闭）：
`/api/test/plans/{id}/events/{seq}/tamper-payload|tamper-hash|delete`、
`/api/test/plans/{id}/tamper-status`、`/api/test/plans/arm-failure/{AFTER_EVIDENCE_INSERT|AFTER_STATE_UPDATE}`、
`/api/test/plans/{id}/internal-state`。

## 4. 数据模型

- `plan`：当前状态（`status`）、来源（`NEW`/`LEGACY`）、`legacy_ref`。
- `plan_evidence_event`：只追加（append-only）证据表；哈希链与幂等键均在此表。
- `legacy_plan`：历史表，锚定迁移只读取，永不改写。

> 运维约定：证据表只允许追加。可进一步用只能 SELECT/INSERT 的应用账号、
> 逻辑复制到只读副本或定期 `pg_dump` 归档来增强防篡改面；这些都不改变本设计。

## 5. 哈希规范化（避免“同数据不同哈希”）

载荷先做**递归字典序排序的规范化 JSON**（无多余空白、UTF-8），再与固定顺序的
`planId\nseq\neventType\nstateBefore(空串表示null)\nstateAfter\nprevHash\nepochMillis\ncanonicalJson`
拼接后做 SHA-256。写入端与校验端共用同一算法（`HashChain`），因此任何库内改动都会在校验时暴露。

## 6. 运行

```bash
# 需要 JDK 17、Maven 3.9+、PostgreSQL
createdb evidence
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/evidence \
SPRING_DATASOURCE_USERNAME=evidence SPRING_DATASOURCE_PASSWORD=evidence \
mvn spring-boot:run

# 历史锚定：启动时自动执行（幂等）
EVIDENCE_ANCHOR_ON_STARTUP=true mvn spring-boot:run
# 或随时手动触发
curl -X POST http://localhost:8080/api/plans/legacy/anchor
```

## 7. 验收测试

测试用 zonky embedded-postgres 启动**真实 PostgreSQL**（非 H2/非 mock），覆盖全部验收点：

```bash
mvn test
```

| 测试类 | 验收点 |
|---|---|
| `FullLifecycleChainTest` | 完整开工→确认→模拟回执→销记链可校验；拒绝分支终态正确 |
| `IdempotencyTest` | 顺序重放 / 并发重复请求只产生一个计划事件；同 key 不同 body 409；回执号重放一致 |
| `TamperDetectionTest` | 改载荷/改哈希/删事件/库外改状态，分别精确定位首个断链 seq |
| `LegacyMigrationTest` | 历史记录不丢失；锚定后可查询可校验；迁移幂等；锚定后可继续流转 |
| `TransactionAtomicityTest` | 证据写入后失败、状态更新后失败均整体回滚，无半成品；失败后可重试 |
| `RestartPersistenceTest` | 重启（全新 Spring 上下文，同一数据库）后链可校验、重放仍幂等 |
| `HashChainTest` | 规范化哈希对 Map 顺序不敏感、任一字段变化导致哈希变化 |

## 8. 威胁模型与边界

- 能防：应用层 bug、重复提交、并发竞态、事务半完成、单行/单列被直接 UPDATE/DELETE 的篡改（校验必报首个断点）。
- 不能单独防：拥有 DBA 权限且能同时重算全链并删除审计副本的攻击者——任何纯软件单节点方案都无法单独防御；
  本设计已把证据限定为 append-only 并预留只读副本/归档扩展点，不引入题目禁止的区块链或外部可信服务。
