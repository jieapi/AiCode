# 自定义提示词

AiCode 的系统提示词可以自己改。默认提示词随 App 内置、升级时自动更新；你只要把想改的片段放进自定义目录就能覆盖，不用动 App 本体。

## 目录结构

提示词放在 AI 配置目录 `~/.aicode/` 下（容器内路径是 `/root/.aicode/`）：

```
~/.aicode/
├── prompts/          默认提示词（App 启动时全量释放，升级自动覆盖）
│   ├── 00-identity.md
│   ├── 10-communication.md
│   ├── ...
│   └── agent/        子目录片段（压缩总结、标题生成等）
├── prompts.custom/   你的自定义覆盖（同名即覆盖，升级不动这里）
│   ├── 50-safety.md  只放你想改的片段
│   └── agent/        子目录同样支持同名覆盖
├── skills/
└── docs/
```

## 加载优先级

每个片段按这个顺序查找，找到就用，不再往后找：

1. `~/.aicode/prompts.custom/<同名文件>` — 你的自定义版本
2. `~/.aicode/prompts/<同名文件>` — 本地默认副本
3. App 内置的版本 — 兜底

所以你只需要放想改的那几个片段，其余会自动用默认版本。

## 怎么改

1. 在 `~/.aicode/prompts.custom/` 下（没有就手动创建）放入要覆盖的片段，**文件名必须和默认片段完全一致**（区分大小写）。子目录里的片段（如 `agent/title-generator.md`）对应放到 `prompts.custom/agent/` 下。
2. 编辑内容。
3. **重启 App 后生效**。

::: warning 必须重启 App，新开会话不算
提示词在 App 进程启动时加载并缓存，新建会话不会重新读文件。改完必须完全退出 App 再打开。
:::

编辑方式有三种：在终端里用 `vi` / `nano` 直接改；让 AI 帮你写（它的文件工具能直接读写这个目录）；或者用文件管理器访问 App 私有目录。

## 升级时的行为

- `prompts/` 目录每次启动都会被内置版本全量覆盖，所以默认提示词会随 App 升级自动更新。
- `prompts.custom/` 目录 App 永远不会自动写入或删除，你的自定义在升级后完整保留。

::: danger 请勿直接修改 prompts/ 目录
`prompts/` 目录中的内容在每次启动时都会被全量重置，手动修改的内容在 App 升级或重启后会被覆盖。如需持久自定义，请务必保存在 `prompts.custom/` 目录中。
:::

## 片段说明

| 文件名 | 内容 |
| --- | --- |
| `00-identity.md` | AI 身份与角色定义 |
| `10-communication.md` | 沟通与回复风格 |
| `15-project-rules.md` | 项目规则加载约定（AGENTS.md / CLAUDE.md），详见 [记忆与项目规则](/guide/memory) |
| `20-coding-discipline.md` | 编码纪律 |
| `30-comments.md` | 代码注释规范 |
| `40-approach.md` | 工作方式与流程 |
| `50-safety.md` | 安全与可信边界 |
| `60-tools-and-paths.md` | 工具说明与路径约定 |
| `70-skills-and-mcp.md` | 技能与 MCP 说明 |
| `80-plan-mode.md` | PLAN 模式提醒 |
| `81-auto-mode.md` | AUTO 模式提醒 |
| `90-subagent-base.md` | 子代理基础运行规范 |
| `agent/compact-summary.md` | 长对话上下文压缩的提示词 |
| `agent/title-generator.md` | 会话标题生成的提示词 |

## 恢复默认

删掉 `~/.aicode/prompts.custom/` 下对应的文件，那个片段就恢复默认版本；删掉整个 `prompts.custom/` 目录则全部恢复。

## 注意

`60-tools-and-paths.md` 这类片段会随工具变更而更新。如果你覆盖了它，App 升级后不会自动获得新版工具说明，AI 看到的工具定义可能和实际不一致。需要更新时手动同步一下，或者删掉你的自定义版本让默认版本重新生效。
