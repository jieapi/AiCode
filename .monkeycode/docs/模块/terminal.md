# terminal — 终端与执行后端模块

提供多标签终端会话、后台命令执行与进程保活；把「本地 PRoot 容器」与「远程 SSH」两种后端统一到同一套 Termux 会话抽象上。

## 结构

```
feature/terminal/
├── domain/
│   ├── TerminalSessionProvider.kt          # 终端后端接口（tab 管理 + 事件流）
│   ├── TerminalSessionManager.kt           # 本地实现：进程内会话池
│   ├── RemoteTerminalSessionManager.kt     # 远程实现：sshj shell channel
│   ├── SshShellBackend.kt                  # 实现 Termux SessionBackend 接 sshj
│   ├── DelegatingTerminalSessionProvider.kt# 按 ExecutionModeHolder 分发
│   ├── TerminalTab.kt                      # tab 数据模型（Running/Finished）
│   ├── TerminalKeepaliveService.kt         # 前台 Service 保活
│   ├── KeepaliveWorker.kt                  # @HiltWorker 周期兜底拉起
│   └── font/、model/                       # 字体管理、主题预设
└── presentation/
    ├── TerminalScreen.kt / TerminalViewModel.kt
    ├── TerminalClients.kt                  # AppTerminalSessionClient 接 session 回调
    └── TerminalSettingsSheet.kt
```

## 关键文件

| 文件 | 目的 |
|------|------|
| `domain/TerminalSessionProvider.kt` | 后端接口：`startBackgroundCommand` / `sendInput` / `getTabOutput` / `listTabs` / `closeTab` / `tabFinishedEvents` |
| `domain/TerminalSessionManager.kt` | 本地会话唯一所有者；退出码兜底监控（解析 `[command exited: N]` 标记，规避 proot 不退出导致 onFinished 不回调） |
| `domain/SshShellBackend.kt` | 远程的关键粘合层：把 sshj `Session.Shell` 包装成 Termux emulator 的输入/输出/resize |
| `domain/TerminalKeepaliveService.kt` | 前台低优先级通知；会话计数归零自动停；`onTaskRemoved` 划卡即停 |
| `domain/KeepaliveWorker.kt` | WorkManager 周期探测保活 Service 被杀则拉起 |

## 依赖

**本模块依赖**:
- `:terminal-emulator` / `:terminal-view` — Termux 派生模块（仿真核心 + 渲染 View）
- `feature/agent` — `RemoteSshConnection`（共享 sshj client）
- `feature/settings` — 保活开关、执行模式

**依赖本模块的**:
- `feature/agent` — `TerminalSessionTool`（AI 后台终端工具）
- `MainActivity` / `WorkbenchPane` — 终端 UI 挂载

## 规范

### 代码模式

**统一抽象**：本地与远程共享同一个 Termux `TerminalSession` + `TerminalEmulator`，区别只在 `SessionBackend`——本地 fork PTY 进程，远程实现 `SessionBackend` 接 sshj。新增后端时实现 `SessionBackend` 并在委托层加分支。

**保活链路**：`AIEditorApp` 监听用户开关统一启停 `TerminalKeepaliveService`（START/STOP_SESSION 按会话计数；ENABLE/DISABLE_PERSISTENT 用户常驻）→ `KeepaliveWorker` 周期兜底 → `START_STICKY` 重建时从 `KeepaliveSettingsRepository` 恢复状态。

### 错误处理

tab 结束码以输出标记解析兜底为准（proot 限制）；SSH 断线由 `RemoteSshConnection` 统一重连。
