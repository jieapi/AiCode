# 记忆与项目规则

本章介绍自动记忆与项目规则两项能力，用于保持 AI 跨会话的上下文连续性与项目一致性。

## 自动记忆

自动记忆是 AI 自行维护的长期知识库。对话中形成的项目约定、用户偏好与架构决策，AI 会自动记录并在后续会话中复用。

### 作用域

| 作用域 | 说明 | 存储位置 |
| --- | --- | --- |
| 全局 | 跨项目通用的个人偏好 | `~/.aicode/memory/` |
| 项目 | 当前项目专属的约定与经验 | 本地模式：`<项目根>/.aicode/memory/`；远程 SSH 模式：`~/.aicode/memory/projects/<项目名>-<标识哈希>/` |

项目记忆在远程模式下存储于手机本地，并按「项目名 + 服务器标识」隔离，不受容器重置影响。

### 记忆文件

每条记忆是一个 Markdown 文件，由 YAML frontmatter（`name`、`description`）与正文组成：

```markdown
---
name: conventions
description: 项目代码规范与命名约定
---
（正文）
```

### 生效方式

会话开始时，系统将全部记忆的摘要（名称与描述）注入提示词；需要详情时，AI 通过 `memory` 工具读取完整内容。

### memory 工具

| 参数 | 说明 |
| --- | --- |
| `action` | `read` / `save` / `edit` / `delete` / `list` |
| `name` | 记忆名称（即文件名） |
| `description` | 摘要，`save` 时必填 |
| `content` | 正文（Markdown），`save` 时必填 |
| `edits` | 局部编辑列表，语义与编辑文件一致 |
| `scope` | `project`（默认）或 `global` |

同名记忆项目级优先于全局。更新既有记忆时建议使用 `edit` 局部编辑；涉及踩坑经验的内容，应在验证根因后记录。

## 项目规则

项目根目录下的 `AGENTS.md` 用于声明项目专属规则、架构约束与构建指南，是 AI 遵循的最高优先级约定；不存在 `AGENTS.md` 时回退读取 `CLAUDE.md`。

### 生效方式

规则文件在会话开始时自动加载，无需手动配置；内容超过 32,000 字符时将被截断。

### 自定义加载约定

项目规则的加载约定对应提示词片段 `15-project-rules.md`，可通过 `~/.aicode/prompts.custom/15-project-rules.md` 覆盖，见[自定义提示词](/guide/custom-prompts)。