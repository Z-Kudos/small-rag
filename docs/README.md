---
title: 文档规范与索引
status: approved
version: 0.0.3
updated: 2026-11-09
owners: [zjk]
related: [ROADMAP.md, DEV-MEMORY.md, README.md]
---

# 文档规范与索引

本目录存放**规划、设计与运维**类文档。目标：一个事实只写一处，读者按角色能快速找到入口。

## 1. 文档分工（单一事实来源）

| 文档 | 读者 | 写什么 | 不写什么 |
| --- | --- | --- | --- |
| `README.md` | 使用者 | 安装、运行、配置、MCP 工具、迁移 | 开发过程、内部实现细节 |
| `ROADMAP.md` | 维护者 | 版本目标、里程碑、验收、非目标 | 类名、方法、参数细节 |
| `docs/plans/` | 开发者 | 版本规划、现状差距、工作包、风险、排期 | 使用教程 |
| `docs/design/` | 开发者 | 跨版本的设计与取舍 | 版本排期 |
| `docs/runbooks/` | 运维 | 部署、排障、故障处理步骤 | 设计论证 |
| `docs/adr/` | 开发者 | 单个不可逆决策及理由 | 实现细节 |
| `DEV-MEMORY.md` | 开发者 / AI | 当前有效状态、坑、待办 | 历史流水、教程 |
| `AGENTS.md` | AI / 贡献者 | 硬约束、构建命令、代码规范 | 长篇设计 |

**判定规则**：同一事实若已在某处定义，其他文档只写一句摘要并链接过去，不复制细节。

## 2. 目录与命名

```text
docs/
├── README.md                              # 本文件：规范与索引
├── templates/
│   └── plan.md                            # 规划模板
├── plans/
│   └── v0.0.3-standalone-and-cluster.md   # 版本规划
├── design/                                # 跨版本设计（按需创建）
├── runbooks/                              # 运维手册（按需创建）
└── adr/                                   # 架构决策记录（按需创建）
```

- 文件名统一 **小写 kebab-case 英文**，不含空格和中文。
- 版本规划：`plans/v<MAJOR>.<MINOR>.<PATCH>-<topic>.md`。
- 设计文档：`design/<topic>.md`。
- 运维手册：`runbooks/<topic>.md`。
- 决策记录：`adr/NNNN-<topic>.md`，四位编号从 `0001` 起，只增不改。

## 3. 文档头（必填）

所有 `docs/` 下的文档、`ROADMAP.md`、`DEV-MEMORY.md` 使用 YAML front matter：

```yaml
---
title: 一句话标题
status: draft | review | approved | implementing | done | superseded
version: 0.0.3
updated: 2026-11-09
owners: [zjk]
related: [ROADMAP.md, DEV-MEMORY.md]
---
```

- `status` 只能取上述值；`updated` 必须是最后修改日期。
- `related` 用相对仓库根目录的路径。
- 发布后不再更新的文档标 `done`；被新文档取代的标 `superseded` 并在正文首行写明替代链接。

## 4. 各类文档的必备章节

### 规划（`plans/`）

1. 背景与目标
2. 范围与非目标
3. 现状与差距
4. 工作包（每项含变更点、验收、风险）
5. 里程碑与排期
6. 验证矩阵
7. 决策记录
8. 完成定义（DoD）

模板见 [`templates/plan.md`](templates/plan.md)。

### 设计（`design/`）

1. 问题
2. 约束
3. 方案对比
4. 选定方案与理由
5. 影响面（接口、数据、兼容性）
6. 回滚方式

### 运维（`runbooks/`）

1. 适用场景
2. 前置检查
3. 操作步骤（可直接复制）
4. 验证
5. 回滚
6. 常见故障

### 决策（`adr/`）

1. 背景
2. 决策
3. 备选方案
4. 后果（正面 / 负面）

## 5. 写作规则

1. 正文中文；代码、路径、标识符、环境变量保留英文原文。
2. 版本号、端口、默认值、测试数必须与代码一致；不确定时写“待核实”，不要猜。
3. 命令必须可直接复制执行，并标注工作目录与前置条件。
4. 用具体日期或版本，避免“目前”“最新”“以后”等易过时措辞。
5. 列举优先用表格；步骤优先用有序列表。
6. 文档内链接用相对路径，不写绝对路径或本机路径。
7. 不记录一次性调试过程；结论进文档，过程交给 `git log`。
8. 修改文档必须同步更新 front matter 的 `updated`。

## 6. 生命周期

```text
draft → review → approved → implementing → done
                                   ↘ superseded
```

- 新版本开始：从模板创建 `plans/vX.Y.Z-<topic>.md`，状态 `draft`。
- 方案确认：改为 `approved`，并把关键决策同步到 `ROADMAP.md`。
- 开始编码：改为 `implementing`。
- 版本发布：改为 `done`，要点合并进 `ROADMAP.md`。
- 下一版本开始：删除上一版本的 plan（历史由 `git log` 保留）；被替代的设计文档标 `superseded`。

## 7. 提交前检查

- [ ] front matter 完整，`updated` 是今天
- [ ] 数字、端口、命令与代码一致
- [ ] 没有复制其他文档已有的细节
- [ ] 相对链接可打开
- [ ] 中文标点统一，代码块标注语言

## 8. 索引

| 文档 | 说明 | 状态 |
| --- | --- | --- |
| [`plans/v0.0.3-standalone-and-cluster.md`](plans/v0.0.3-standalone-and-cluster.md) | v0.0.3 单机与集群规划 | approved |
| [`templates/plan.md`](templates/plan.md) | 规划文档模板 | — |
| [`../ROADMAP.md`](../ROADMAP.md) | 版本里程碑与验收 | — |
| [`../DEV-MEMORY.md`](../DEV-MEMORY.md) | 当前开发状态 | — |
| [`../README.md`](../README.md) | 使用与部署 | — |
