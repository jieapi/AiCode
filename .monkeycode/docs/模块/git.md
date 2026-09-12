# git — Git 可视化模块

基于容器内命令行 `git`（非 JGit）的可视化 Git 面板：状态、分支、提交历史（含泳道拓扑图）、差异、标签、暂存/回退与凭据配置。

## 结构

```
feature/git/
├── domain/
│   ├── GitRepository.kt          # 全部 git 子命令聚合（status/branches/log/commit/push/pull...）
│   ├── GitGraphBuilder.kt        # 纯 Kotlin 泳道拓扑布局（供 Canvas 绘制）
│   ├── GitErrorMessage.kt        # git 英文 stderr → 中文友好提示
│   ├── GitCommandFailureException.kt
│   └── model/                    # GitModels.kt / GitGraphModels.kt 领域模型
└── presentation/
    ├── GitViewModel.kt
    └── component/
        ├── GitScreen.kt / GitStatusTab.kt / GitBranchesTab.kt / GitLogTab.kt
        └── DiffViewer.kt         # 语法高亮 diff 视图
```

## 关键文件

| 文件 | 目的 |
|------|------|
| `domain/GitRepository.kt` | 经 `CommandEngine.runCommandSyncUnbounded` 执行 git（cwd=当前工作区），逐参数 shell 转义拼 `/bin/sh -c`；统一带 `-c core.quotepath=false` 防中文路径乱码。读命令 `git()`，写命令 `gitChecked()`（非零退出抛 `GitCommandFailureException` 携带 git 输出） |
| `domain/GitGraphBuilder.kt` | 解析 `git log`（0x1f 分隔字段）做泳道布局，输出 `GitGraph` 领域模型 |
| `domain/GitErrorMessage.kt` | stderr 模式匹配转译（冲突/无凭据/网络错误等） |

## 依赖

**本模块依赖**:
- `feature/agent` — `CommandEngine`（容器内执行）
- `feature/settings` — 执行模式
- `feature/credentials` — 凭据（`credential.helper=store` + 自定义 helper 注入，不经 agent 工具链/权限引擎）

**依赖本模块的**:
- `MainActivity` / `WorkbenchPane` — Git 面板挂载

## 规范

### 代码模式

**命令执行**：一律走 `CommandEngine`，保持本地容器/远程 SSH 一致；拼命令必须逐参数转义，禁止拼接用户输入。

**错误处理**：写命令失败抛 `GitCommandFailureException`，UI 层经 `GitErrorMessage` 转中文提示展示。

### 与 AI 的边界

Git 面板的凭据配置直接读写凭据仓库，不经 AI 工具链与授权弹窗；AI 侧的 git 操作则走 `Bash` 工具受权限体系管控。
