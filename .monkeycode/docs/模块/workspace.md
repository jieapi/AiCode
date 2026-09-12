# workspace — 工作区与文件访问模块

管理项目工作区（本地私有目录 / 远程 SSH 目录），提供统一的文件读写后端，并承担 SAF 导出、SFTP/FTP 同步与内置 FTP 服务端。

## 结构

```
feature/workspace/
├── domain/
│   ├── FileAccessProvider.kt           # 文件读写后端接口
│   ├── LocalFileAccess.kt              # 本地实现（fsync + 回读校验）
│   ├── RemoteSftpFileAccess.kt         # 远程实现（SSH exec channel）
│   ├── DelegatingFileAccess.kt         # 按执行模式分发
│   ├── WorkspacePathMapper.kt          # 容器路径 ⇄ 宿主路径（profile 感知）
│   ├── WorkspaceRepository.kt          # 工作区/项目管理（data/repository/）
│   ├── UriPathResolver.kt              # SAF uri → 路径
│   └── remote/                         # 同步子系统
│       ├── SyncEngine.kt               # FileObserver 监听 + gitignore + 防抖批量上传
│       ├── SftpSyncClient.kt / FtpSyncClient.kt / LocalSyncClient.kt
│       └── FtpServerManager.kt         # 内置 FTP 服务端
├── data/
│   ├── local/                          # RemoteConnectionDao/Entity、RemoteMountEntity
│   └── provider/WorkspaceDocumentsProvider.kt  # SAF DocumentsProvider
└── presentation/                       # 工作区选择、远程连接管理 UI
```

## 关键文件

| 文件 | 目的 |
|------|------|
| `domain/FileAccessProvider.kt` | 接口：read / write / list / delete / copy / rename / mkdirs 等，路径统一为 AI 视角的容器路径（`~/workspace/...`） |
| `domain/RemoteSftpFileAccess.kt` | 用 SSH exec channel 执行 `cat` / 重定向 / base64 读写远程文件——刻意避开 sshj 0.38.0 SFTP 的必现 Buffer 溢出 bug |
| `domain/WorkspacePathMapper.kt` | 容器内路径 ⇄ 宿主真实路径互转：`~/workspace` → bind mount 工作区；其它容器绝对路径 → 当前 profile rootfs |
| `data/repository/WorkspaceRepository.kt` | 本地项目在 `filesDir/projects/<name>`（ext4 支持 symlink）；远程为 SSH 服务器 `remoteWorkspacePath` 子目录；当前工作区名持久化 DataStore |
| `data/provider/WorkspaceDocumentsProvider.kt` | 把 `filesDir` 暴露给系统文件 app，白名单只放 `projects/` 与 `aicode/` |
| `domain/remote/SyncEngine.kt` | 文件同步引擎：FileObserver + gitignore/自定义忽略 + 防抖批量上传 + 断线重连 |

## 依赖

**本模块依赖**:
- `feature/agent` — `RemoteSshConnection`
- `feature/settings` — `ExecutionModeHolder`、容器 profile
- `core/util` — `GitIgnoreMatcher`

**依赖本模块的**:
- `feature/agent` — AI 文件工具（readFile / writeFile / editFile / search 等）
- `feature/editor` — 文件树与编辑器读写
- `feature/git` — 工作区目录定位

## 规范

### 代码模式

**路径体系**：上层一律传 AI 视角容器路径，`WorkspacePathMapper` 负责映射到宿主真实路径。直接操作宿主绝对路径会破坏容器/远程一致性。

**写侧完整性**：本地写文件 fsync + 回读长度校验；远程写经 base64 编码规避 shell 转义问题。

### 错误处理

远程文件操作失败统一带 stderr 上下文抛出，由 AI 工具层转为 `ToolResult.Error` 回传。
