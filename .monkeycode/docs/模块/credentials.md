# credentials — Git 凭据模块

Git 凭据的文件化存储与容器内注入：App、容器内 git、远程服务器 git 三方共用同一份 git-credential-store 格式文件；git 缺凭据时经文件 IPC 弹窗向用户索要。

## 结构

```
feature/credentials/
├── domain/
│   ├── repository/CredentialRepository.kt   # 接口
│   └── model/GitCredential.kt               # host/username/token，id 即 host
├── data/
│   ├── repository/FileCredentialRepository.kt  # 文件仓库实现
│   ├── CredentialRequestBridge.kt           # helper ↔ App 文件 IPC 桥
│   ├── CredentialInjectSettingsRepository.kt # 远程注入开关（按 host 隔离）
│   └── LegacyCredentialMigrator.kt          # 旧 Room 凭据表一次性迁移
└── presentation/component/
    ├── CredentialScreen.kt / CredentialEditorScreen.kt
    ├── CredentialPromptDialog.kt
    └── GlobalCredentialDialogHost.kt        # 全局弹窗宿主
```

## 关键文件

| 文件 | 目的 |
|------|------|
| `data/repository/FileCredentialRepository.kt` | 真源是文件 `filesDir/aicode/git-credentials`（容器内 `/root/.aicode/git-credentials`），git-credential-store 标准格式（每行 `https://user:token@host`）；内存 StateFlow 缓存 + Mutex 原子落盘 |
| `data/CredentialRequestBridge.kt` | git 缺凭据时容器内 helper（`assets/aicode/git-credential-aicode`）写 `cred-req-<id>` 请求文件 → `FileObserver` 捕获 → StateFlow 供全局 Compose 弹窗 → 用户回填写 `cred-resp-<id>` → helper 轮询取走喂回 git；含低频兜底轮询、与 `LinuxContainerEngine` 在途计数联动（暂停 120s 超时杀进程 watchdog） |
| `data/CredentialInjectSettingsRepository.kt` | 「自动注入凭据到远程服务器」开关，SharedPreferences 按 `MD5(host:port)` 前 8 位每服务器隔离 |

## 依赖

**本模块依赖**:
- `feature/agent` — `LinuxContainerEngine`（在途命令计数联动）
- `core/util` — `FileLogger`

**依赖本模块的**:
- `feature/git` — Git 面板凭据配置
- `feature/settings` — 凭据相关设置 UI
- `di/AgentModule` — SSH host/login key store

## 规范

### 代码模式

**文件即契约**：凭据文件格式是 git-credential-store 标准，App 侧与容器内 helper 双方读写同一份；任何一方改格式都会断开另一方。

**IPC 生命周期**：`CredentialRequestBridge.start()` 在 `AIEditorApp.onCreate` 启动；请求/响应文件用唯一 id 配对，弹窗取消则留空让 helper 超时。

### 安全

凭据仅存 App 私有目录，备份导出走 [backup 模块](./backup.md) 加密；日志中不得打印 token。
