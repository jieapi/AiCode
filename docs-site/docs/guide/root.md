# Root 执行后端

Root 让 AiCode 以**超级用户（uid 0）**身份在 Android 系统上执行命令。相比 Shizuku 的 adb shell（uid 2000），root 可以访问系统受限目录、执行需要超级用户权限的操作，弥补 shell 身份做不到的部分。

它与「本地容器 / 远程 SSH」并列，也不需要切换模式：只要设备已 root 且授权通过，AI 就能调用名为 `Root` 的工具。

## 能做什么

| 能力 | 本地容器（PRoot） | Shizuku（shell） | Root |
| --- | :---: | :---: | :---: |
| `pm` / `am` / `cmd` 等系统命令 | ✗ | ✓ | ✓ |
| 读写 `/sdcard` | 有限 | ✓ | ✓ |
| 访问其他应用私有目录 `/data/data` | ✗ | ✗ | ✓ |
| 读写 `/data/adb`、改系统属性 | ✗ | ✗ | ✓ |
| 需要设备 root | 否 | 否 | **是** |

Root 权限最高，请谨慎授权。

## 前置：设备已 root

需要设备已通过 Magisk / KernelSU / APatch 等方案获取 root，且 root 管理器提供了 `su`。常见路径包括 `/system/bin/su`、`/system/xbin/su`、`/sbin/su`、`/debug_ramdisk/su` 等，AiCode 会自动探测。

## ⚠️ 先搞清楚：容器路径 ≠ 真机路径

AiCode 里有两套文件系统视图，**同一个路径在两边含义不同**：

- `Bash`、`terminal`、文件树、`readFile`/`writeFile` 等都在 **Linux 容器内**；
- `Root`（以及 `Shizuku`）直接作用于 **宿主真机**。

| 你想操作 | 容器工具用（Bash 等） | Root 用（真机） |
| --- | --- | --- |
| 当前工作区文件 | `~/workspace/xxx` | `/data/user/0/<包名>/files/projects/<项目名>/xxx` |
| AI 配置 | `~/.aicode/xxx` | `/data/user/0/<包名>/files/aicode/xxx` |
| 容器内系统文件 | `/etc/xxx` | `/data/user/0/<包名>/files/rootfs/etc/xxx` |
| 真机系统文件 | 看不到 | `/system/xxx`、`/data/xxx` |
| 手机存储 | 经挂载点映射 | `/sdcard/xxx` |

`<包名>`：正式包为 `com.aicode`，debug 测试包为 `com.aicode.debug`。

**所以**：改项目里的文件，用 `Bash` 或文件树；要动 Android 系统本身（`pm`/`am`、`/data/data`、系统属性），才用 `Root`。
如果你让 AI 用 `Root` 去操作 `~/workspace`，那是无效的——那不是真机路径。

## 直接访问手机上的受限目录

Android 11 起，普通 App 和 adb shell 都被限制访问 `/Android/data/<其它应用>/` 这类目录，
于是常见做法是"申请权限"或"挂载"。**用 Root 不需要这些**——uid 0 在宿主上读写任意路径都不受限制。

例：读取 QQ 接收的文件目录

```
ls /storage/emulated/0/Android/data/com.tencent.mobileqq/Tencent/QQfile_recv/
```

把文件取进当前工作区时，直接一条命令拷过去即可（工作区在真机上的路径见上表），不需要挂载：

```
cp '/storage/emulated/0/Android/data/com.tencent.mobileqq/Tencent/QQfile_recv/xxx.pdf' \
   /data/user/0/<包名>/files/projects/<项目名>/
```

拷完就能在文件树 / `Bash` 里用 `~/workspace/xxx.pdf` 正常处理。

## 在 AiCode 中授权

打开「设置 → 运行环境 → Root」，页面会显示当前状态：

- **不可用**：未检测到 `su`，设备可能未 root，或 root 方案未提供 `su`。
- **未授权**：检测到 `su`，但尚未获得授权。
- **已就绪**：可以 root 身份执行命令。

Root 没有像 Shizuku 那样的可编程授权接口，授权由 **root 管理器自己的弹窗**完成。因此：

- 点击页面上的 Root 条目会触发一次探测，此时 root 管理器会弹出授权框，选择「允许」即可；
- 首次让 AI 调用 `Root` 工具时，同样会弹出授权框；
- 部分 root 管理器支持「记住选择」，之后不再重复询问。

## 使用与安全

- AI 调用 `Root` 工具时会走**工具授权**弹窗（与 `Bash` / `Shizuku` 一致），可选择单次放行或「始终允许」记住命令前缀。
- 命令按前缀做指令级匹配：例如记住 `pm` 后，后续 `pm ...` 命令自动放行，其他命令仍会询问。
- Plan（计划）模式下，该工具与其他写操作一样被拦截。
- Root 权限极高，误操作可能影响系统稳定性。建议只在确有需要时授权，并留意 AI 请求执行的命令内容。

## 常见问题

**状态一直是「不可用」**

说明未检测到 `su`。确认设备确实已 root，且 root 管理器正常工作；部分方案（如仅 Magisk 隐藏、未真正提供 su）不会有 `su`。

**命令报错或没有输出**

- 首次执行时请在设备上留意 root 管理器的授权弹窗，未允许则命令拿不到 root。
- 部分命令即使 root 也可能受 SELinux 策略限制，可查看命令自身的报错信息。
- 超时后 `su` 进程会被强杀，但其派生的子进程可能残留，属已知限制。

**与 Shizuku 该用哪个？**

只做常规系统命令、读写 `/sdcard`，用 Shizuku（无需 root）即可；需要访问 `/data/data`、`/data/adb` 或需要 uid 0 的操作，才用 Root。
