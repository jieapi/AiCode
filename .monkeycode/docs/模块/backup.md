# backup — 备份恢复模块

加密备份与恢复全量数据：provider 配置、凭据、聊天历史、待办、检查点元数据、工作区文件与各域设置。流式实现，内存占用与数据量解耦。

## 结构

```
feature/backup/
├── domain/
│   ├── BackupManager.kt        # 接口：export / import / previewImport / exportSession
│   ├── BackupCrypto.kt         # PBKDF2 + AES-GCM
│   └── BackupSnapshot.kt       # 快照 DTO（schemaVersion 兼容性校验）
├── data/
│   └── BackupManagerImpl.kt    # 实现：跨 Room + DataStore 采集/还原
└── presentation/
    ├── BackupSection.kt        # 设置页备份 UI
    └── BackupViewModel.kt
```

## 关键文件

| 文件 | 目的 |
|------|------|
| `domain/BackupManager.kt` | 导出 = 按 `BackupOptions` 采集快照 → 流式序列化（metadata.json + 各 `*.jsonl`）→ tar.gz → 口令非空则 AES-GCM 加密；导入反向，支持 `previewImport` 先校验并列出可勾选的工作区 |
| `domain/BackupCrypto.kt` | PBKDF2WithHmacSHA256（21 万迭代）派生密钥 + AES/GCM；salt+IV 写文件头；手动 64KB 分块 `Cipher.update/doFinal`（刻意避开 `CipherInputStream` 吞 GCM 校验异常的坑）；口令错误抛 `BackupDecryptionException` |
| `data/BackupManagerImpl.kt` | Apache commons-compress 流式 tar.gz；跨 Room 各 DAO + 各 DataStore 设置仓库采集/还原 |
| `domain/BackupSnapshot.kt` | 与 Room Entity 同构但解耦的 `@Serializable` DTO，`schemaVersion` 做兼容性校验 |

## 依赖

**本模块依赖**:
- `AgentDatabase` 全部 DAO
- 各 feature 的 DataStore 设置仓库
- commons-compress

**依赖本模块的**:
- `feature/settings` 的备份设置页

## 规范

### 代码模式

**DTO 解耦**：备份 DTO 与 Room Entity 分离（同构映射），Entity 字段演进不直接破坏旧备份兼容性；不兼容变更递增 `schemaVersion` 并在导入侧分支处理。

**流式**：所有序列化/压缩/加密均为流式管道，新增备份内容时保持 chunked 写入，避免整包载入内存。

### 加密

改动加密实现前先读 `BackupCrypto.kt` 注释（分块 doFinal 的原因）；保持文件头格式（salt+IV）向后兼容，否则旧备份无法解密。
