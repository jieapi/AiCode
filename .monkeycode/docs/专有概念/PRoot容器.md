# PRoot 容器

AiCode 本地执行后端的底层机制：不需要 root 权限，在 App 私有目录里运行一个 Alpine Linux 用户态容器，AI 的 Shell 命令、MCP stdio 服务器、Git 操作都跑在其中。

## 什么是 PRoot 容器？

PRoot 是用户态的 chroot/挂载模拟器：它以 ptrace 跟踪客户进程的系统调用，把容器内路径（如 `/root/workspace`）透明重定向到宿主真实路径（App 私有目录），并模拟 root 权限。AiCode 用它加载内置的 Alpine rootfs（`assets/container/` 下的 tar.gz，按 flavor 提供 arm/x86 两套）。

**关键特征**:
- **免 root**：客户机二进制由 proot loader 用 `mmap(PROT_EXEC)` 映射进内存执行，绕开内核 execve 的 W^X / SELinux 限制
- **proot 本体走 jniLibs**：以 `lib*.so` 命名打进 APK，安装后解压到 `nativeLibraryDir`——App 目录中唯一允许 execve 的位置
- **rootfs 在 assets**：`ContainerInstaller` 首次启动解压到 `filesDir`（容器 profile 目录）
- **宿主目录 bind mount**：工作区项目经 proot `-b` 参数挂进容器内 `~/workspace`

## 代码位置

| 方面 | 位置 |
|------|------|
| 命令执行入口 | `feature/agent/domain/container/LinuxContainerEngine.kt`（`buildProotInvocation` 组装 proot 命令行） |
| 命令引擎抽象 | `feature/agent/domain/container/CommandEngine.kt` |
| 容器安装与 profile | `ContainerInstaller` / `ContainerProfile`（`feature/settings/data/` 或容器相关仓库） |
| 镜像清单 | `app/src/main/assets/container-images.json` |
| rootfs 二进制 | `app/src/_armAssets/container/arm/`、`app/src/_x86Assets/container/x86/` |
| proot 二进制 | `app/src/_armJniLibs/`、`app/src/_x86JniLibs/`（`libproot.so` + loader + `libtalloc` + `libandroid-shmem`） |
| 容器内初始化脚本 | `app/src/main/assets/aicode/provision.sh`（提取到 `filesDir/aicode`，容器内为 `/root/.aicode`） |
| 路径映射 | `feature/workspace/domain/WorkspacePathMapper.kt`（容器路径 ⇄ 宿主路径） |

## 构建打包约束

容器二进制的打包方式由 `app/build.gradle.kts` 精细控制，改动前先读注释：

- `packaging.jniLibs.useLegacyPackaging = true` — 必须：否则 `.so` 不解压、直接从 APK 映射，`execve libproot.so` 会得到 ENOENT
- `keepDebugSymbols` 显式保留 proot 全套 — 外部预编译产物不得被构建流程后处理
- `_armJniLibs` / `_x86JniLibs` 按 flavor 共享 sourceSet — 单架构包只含一套二进制

## 不变量

1. **proot 文件名必须是 `lib*.so`**：改名会失去 jniLibs 解压到 `nativeLibraryDir` 的待遇，SELinux 直接禁止 execve
2. **targetSdk 锁定 28**：Android 10+ 收紧 W^X 后，App 可写目录执行二进制的合法路径只有 nativeLibraryDir；升 targetSdk 需重新论证整条容器链路
3. **容器内二进制由 loader 映射执行**：rootfs 内的 bash/python 等不经过内核 execve，故不受 W^X 约束——这套机制成立的前提是 proot loader 本身能被 execve

## 与远程模式的关系

远程 SSH 模式完全绕开 PRoot：命令走 sshj exec channel（`RemoteSshEngine`），终端走 shell channel（`SshShellBackend`）。MCP stdio 服务器在远程模式下跑在远程服务器的默认容器/环境中。分发逻辑见[执行模式](./执行模式.md)。
