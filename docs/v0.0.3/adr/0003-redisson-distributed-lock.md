---
title: 分布式锁使用 Redisson
status: done
version: 0.0.3
updated: 2026-11-10
owners: [zjk]
template: docs/templates/adr.md
related: [AGENTS.md, ROADMAP.md, docs/v0.0.3/plan.md, docs/v0.0.3/design/architecture.md]
---

# ADR-0003：分布式锁使用 Redisson

## 1. 背景

- 应用当前用 `RedisDistributedLock` 自行实现 Redis 租约锁（`SET NX PX` + Lua 续租/释放 + `INCR <key>:epoch`）。
- 自行维护锁脚本与 fencing token 增加了行为与边界的维护成本；Redisson 已提供符合语义的 `RFencedLock`。
- 应用仍需要 fencing token 保护 Qdrant 写入，且不能回退到“普通 lock 就够了”。

## 2. 决策

**distributed 模式的分布式锁统一使用 Redisson `RFencedLock`；standalone 模式保留进程内锁。**

| 组件 | 结论 | 用途 / 理由 |
| --- | --- | --- |
| `redisson`（核心） | 引入，替换 `spring-boot-starter-data-redis` | distributed 锁实现 |
| `RFencedLock` | 使用 | 每次获取返回严格递增 token，作为 fencing token |
| `RedissonDistributedLock` | 新增 | 实现应用内 `DistributedLock` 接口 |
| `LocalDistributedLock` | 保留 | standalone 零依赖 |
| Redis owner 诊断 bucket | 接受 | `<lock-key>:owner` 只记录持有者实例 id，不参与互斥 |
| Redisson watchdog | 启用 | 进程存活期间自动续租；进程退出后停止续租，TTL 过期接管 |

**语义约定**

1. `RFencedLock.tryLockAndGetToken(0, -1, MILLISECONDS)`：不等待；Redisson watchdog 自动续租。
2. `isHeld` = `getToken() == handle.token && isLocked()`；旧持有者 token 落后即拒绝写入。
3. `release` 只允许最新持有者 `forceUnlock`；旧持有者恢复后不会误删新持有者的锁。
4. `renew` 不直接续租（watchdog 已负责），只校验状态并刷新 owner 诊断镜像。
5. `IndexActivityGuard` / `SourcesLeaderElection` 在 `@PreDestroy` 主动释放锁；崩溃时靠 watchdog 停止续租 + TTL 接管。
6. 应用不再直接依赖 `spring-boot-starter-data-redis` / Lettuce；Redis 健康检查由 `RedissonHealthIndicator` 提供。

## 3. 备选方案

- **继续维护自定义 Redis 锁**：可控但重复 Redisson 已成熟的能力，脚本、token、续租边界都需要长期维护。
- **使用普通 `RLock`**：能互斥，但没有 fencing token，无法满足“旧持有者恢复后不能继续写”的要求。
- **引入 `redisson-spring-boot-starter`**：会替换 Spring Data Redis 连接工厂并带来自动装配副作用；当前只需要锁，核心 `redisson` 足够。

## 4. 后果

正面：

- 锁实现从自研脚本收敛到 Redisson 标准语义，行为、续租与故障处理更可预期。
- fencing token 由 Redisson 保证单调递增，应用只做校验，不做计数。
- 移除 Spring Data Redis / Lettuce 依赖，distributed 仍只通过 Redisson 访问 Redis。

负面：

- 应用从自研 Redis 锁切换到 Redisson，锁实现的诊断方式变化（owner 为诊断镜像，不是互斥依据）。
- Redisson watchdog 会在进程存活期间自动续租，因此实例优雅退出必须主动释放；崩溃仍靠 TTL 接管。
- 应用增加 Redisson 依赖，需要接受其 Netty / codec 体积与升级节奏。
