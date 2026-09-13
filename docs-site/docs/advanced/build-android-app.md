# 在容器中编译 Android 应用

在 AiCode 的 Linux 容器里搭好 JDK 与 Android SDK，就能直接从源码编译 Android 应用。本文以 Debian 12 (bookworm) aarch64 为例，Ubuntu、Arch 等发行版同样适用。

::: tip 建议使用自定义 Debian / Ubuntu 镜像
内置的 Alpine 容器用的是 musl libc，与 PRoot 交互时编译 Android 应用需要额外处理（见文末补充）。导入自定义镜像的方法见「自定义容器镜像」。
:::

## 环境基线

| 项 | 值 |
| --- | --- |
| 系统 | Debian GNU/Linux 12 (bookworm) aarch64 |
| JDK | OpenJDK 17 |
| Gradle | 项目 wrapper 自带，无需系统安装 |
| SDK | Android 36 / build-tools 35.0.0 |
| aapt2 | ARM64 静态编译版 35.0.2（社区构建） |

## 1. 安装基础依赖

```bash
apt update
apt install -y openjdk-17-jdk-headless curl wget unzip ripgrep
```

验证 JDK：

```bash
/usr/lib/jvm/java-17-openjdk-arm64/bin/java -version
# 应输出 openjdk version "17..."
```

## 2. 安装 Android SDK

默认使用国内镜像安装（下载更快、更稳）。除 sdkmanager 本体（cmdline-tools）需手工下载解压外，其余组件通过 `SDK_TEST_BASE_URL` 环境变量覆盖 sdkmanager 的默认仓库根 URL（需以 `/` 结尾，镜像需提供 `repository2-3.xml`），一条命令全部装完（`--list` 与 `--install` 均已实测可用）：

```bash
# 唯一手工步骤：获取 sdkmanager 本体（从镜像下载）
mkdir -p ~/android/sdk && cd ~/android/sdk
curl -fL -C - -o clt.zip https://mirrors.cloud.tencent.com/AndroidSDK/commandlinetools-linux-13114758_latest.zip
unzip -q clt.zip
mkdir -p cmdline-tools/latest && mv cmdline-tools/* cmdline-tools/latest/

# 组件全部走镜像安装（自动处理目录布局与许可接受）
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64
export ANDROID_HOME=$HOME/android/sdk
export PATH=$ANDROID_HOME/cmdline-tools/latest/bin:$PATH
export SDK_TEST_BASE_URL=https://mirrors.cloud.tencent.com/AndroidSDK/

yes | sdkmanager --install "platforms;android-36" "build-tools;35.0.0" "platform-tools"
```

::: tip 版本说明
按目标项目的 `compileSdk` 和 `buildToolsVersion` 选择组件版本，本文示例为 Android 36 / build-tools 35.0.0。
:::

::: tip 镜像与官方源
`SDK_TEST_BASE_URL` 虽为测试用途的环境变量（非官方文档化的常规配置），但已被镜像方案广泛使用。镜像安装的 SDK 与官方安装完全等价：即使网络可直接访问 `dl.google.com`，也无需还原为官方流程。若该方式在特定场景下失效，可改用官方源（见下）或手工组装。
:::

### 可选：官方源安装

若网络可直连 `dl.google.com`，也可使用官方标准流程：

```bash
mkdir -p ~/android/sdk && cd ~/android/sdk
curl -O https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip
unzip -q commandlinetools-linux-*.zip
mkdir -p cmdline-tools/latest && mv cmdline-tools/* cmdline-tools/latest/

export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64
export ANDROID_HOME=$HOME/android/sdk
export PATH=$ANDROID_HOME/cmdline-tools/latest/bin:$PATH

yes | sdkmanager --install "platforms;android-36" "build-tools;35.0.0" "platform-tools"
```

### 备选：从镜像手工组装 SDK

若 `SDK_TEST_BASE_URL` 方式不可用，可从腾讯云镜像下载组件 zip 手工组装，最终得到 `build-tools/`、`platforms/`、`platform-tools/` 三个目录，效果与 sdkmanager 一致：

| 组件 | 镜像地址 |
| --- | --- |
| commandlinetools | `https://mirrors.cloud.tencent.com/AndroidSDK/commandlinetools-linux-13114758_latest.zip` |
| platform-36 | `https://mirrors.cloud.tencent.com/AndroidSDK/platform-36_r01.zip` |
| build-tools 35.0.0 | `https://mirrors.cloud.tencent.com/AndroidSDK/build-tools_r35_linux.zip` |
| platform-tools | `https://mirrors.cloud.tencent.com/AndroidSDK/platform-tools_r35.0.2-linux.zip` |

```bash
cd /workspace
curl -L -O https://mirrors.cloud.tencent.com/AndroidSDK/platform-36_r01.zip
curl -L -O https://mirrors.cloud.tencent.com/AndroidSDK/build-tools_r35_linux.zip
curl -L -O https://mirrors.cloud.tencent.com/AndroidSDK/platform-tools_r35.0.2-linux.zip

# 1) build-tools：zip 解压出的目录是 android-15/，把内容移到 build-tools/35.0.0/
unzip build-tools_r35_linux.zip -d ~/android/sdk
mkdir -p ~/android/sdk/build-tools/35.0.0
mv ~/android/sdk/android-15/* ~/android/sdk/build-tools/35.0.0/

# 2) platform：解压出 android-36/
unzip platform-36_r01.zip -d ~/android/sdk
mkdir -p ~/android/sdk/platforms
mv ~/android/sdk/android-36 ~/android/sdk/platforms/android-36

# 3) platform-tools：解压即得 platform-tools/，无需移动
unzip platform-tools_r35.0.2-linux.zip -d ~/android/sdk

ls ~/android/sdk   # 应看到 build-tools/ platforms/ platform-tools/ 三个目录
```

## 3. 替换 ARM64 原生二进制

Google 官方的 `aapt2`、`adb` 等工具是 x86_64 编译的，在 aarch64 上跑不起来，需要换成社区维护的 ARM64 静态编译版本。

从 `https://github.com/lzhiyong/android-sdk-tools/releases/download/35.0.2/android-sdk-tools-static-aarch64.zip` 下载。下载慢或失败时，可以在原链接前加 GitHub 代理前缀（实测可用，2026-08）：

| 加速服务 | 用法 |
| --- | --- |
| gh-proxy.com | `https://gh-proxy.com/https://github.com/...` |
| ghproxy.vip | `https://ghproxy.vip/https://github.com/...` |

```bash
# 示例：走 gh-proxy.com 加速下载
curl -L -O https://gh-proxy.com/https://github.com/lzhiyong/android-sdk-tools/releases/download/35.0.2/android-sdk-tools-static-aarch64.zip
# 容器能直连 GitHub 时可直接下载
# curl -L -O https://github.com/lzhiyong/android-sdk-tools/releases/download/35.0.2/android-sdk-tools-static-aarch64.zip
unzip /workspace/android-sdk-tools-static-aarch64.zip -d ~/armtools35
```

替换 SDK 里的二进制：

```bash
cp -p ~/armtools35/build-tools/* ~/android/sdk/build-tools/35.0.0/
cp -p ~/armtools35/platform-tools/* ~/android/sdk/platform-tools/
```

验证 `aapt2` 可执行：

```bash
~/android/sdk/build-tools/35.0.0/aapt2 version
# 应输出版本号，不报错
```

## 4. 配置

### local.properties（项目根目录）

```bash
echo "sdk.dir=/root/android/sdk" > /workspace/local.properties
```

这个文件通常已被 `.gitignore` 忽略，不会污染仓库。

### 全局 Gradle 配置

aapt2 覆盖配置写到全局文件，不要写进项目的 `gradle.properties`：

```bash
mkdir -p ~/.gradle
echo "android.aapt2FromMavenOverride=/root/android/sdk/build-tools/35.0.0/aapt2" > ~/.gradle/gradle.properties
```

### apt 源（可选）

容器默认可能连不上官方源，建议换国内镜像：

```
URIs: http://mirrors.ustc.edu.cn/debian
URIs: http://mirrors.ustc.edu.cn/debian-security
```

### 下载 Gradle 发行版（可选）

项目用 Gradle wrapper 时不需要系统 Gradle，但从零搭建新项目时要先有一个发行版。官方源 `https://services.gradle.org/distributions/gradle-8.11.1-bin.zip` 慢的话可用镜像：

- 华为云：`https://mirrors.huaweicloud.com/gradle/gradle-8.11.1-bin.zip`
- 腾讯云：`https://mirrors.cloud.tencent.com/gradle/gradle-8.11.1-bin.zip`

```bash
curl -L -o /workspace/gradle.zip https://mirrors.huaweicloud.com/gradle/gradle-8.11.1-bin.zip
unzip gradle.zip -d /workspace/tools
/workspace/tools/gradle-8.11.1/bin/gradle --version   # 用它跑构建或生成 wrapper
```

## 5. 编译

```bash
cd /workspace
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64 \
ANDROID_HOME=/root/android/sdk \
./gradlew assembleDebug
```

依赖下载慢时，可以在 `settings.gradle.kts` 的 `dependencyResolutionManagement.repositories` 里改用阿里云镜像（AGP 默认从 `google()` / `mavenCentral()` 拉依赖）：

```kotlin
maven { url = uri("https://maven.aliyun.com/repository/google") }
maven { url = uri("https://maven.aliyun.com/repository/central") }
maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
```

首次构建大约 10 到 15 分钟（下载依赖加 Kotlin 编译），之后增量构建 1 到 2 分钟。Gradle daemon 在 PRoot 下可以正常工作，能加速增量构建；如果遇到 daemon 异常退出导致锁文件冲突，单次加 `--no-daemon` 即可。

成功后 APK 的输出路径取决于项目配置，通常是：

```
app/build/outputs/apk/<flavor>/debug/app-<flavor>-debug.apk
```

## 常见问题

| 现象 | 原因 | 解决 |
| --- | --- | --- |
| `JAVA_HOME is not set` | 未设环境变量 | 命令行加 `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64` |
| `SDK location not found` | 缺 local.properties | 创建 `local.properties` 写入 `sdk.dir=/root/android/sdk` |
| `aapt2 syntax error` | 用了 x86 版 aapt2 | 确认 `~/.gradle/gradle.properties` 里的 `aapt2FromMavenOverride` 指向 ARM 版 |
| `Failed to install build-tools` | AGP 联网验证失败 | 确认本地已装项目所需的 build-tools 版本 |
| apt 下载 403 | 镜像源临时不可用 | 换镜像源（清华 → 中科大） |
| Gradle 锁文件冲突 | 上次构建未正常退出 | 删除 `~/.gradle/caches/*.lock`，或加 `--no-daemon` |

## 补充：内置 Alpine 容器的修复

在内置 Alpine 容器里编译 Android 应用，可能遇到 `Failed to delete /tmp/tempdir_*`。原因是 AGP 的 apkzlib 里 `TemporaryFile` 用 `File.delete()` 删临时目录，这个操作在 PRoot 环境下可能失败。自定义 Debian 镜像不受影响。

::: warning 版本相关
下述代码针对 Gradle 8.11.1 / AGP 8.x 中的 apkzlib（包名 `com.android.tools.build.apkzlib.bytestorage.TemporaryFile`）。不同版本的包路径与内部结构可能不同，请按实际使用的 jar 反编译结构调整。
:::

把 `File.delete()` 换成 `Files.delete()`（走 unlinkat 绕过底层删除失效）：

```java
package com.android.tools.build.apkzlib.bytestorage;

import com.google.common.base.Preconditions;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;

public class TemporaryFile implements Closeable {
    private boolean deleted;
    private final File file;

    public TemporaryFile(File file) {
        this.deleted = false;
        this.file = file;
    }

    public File getFile() {
        Preconditions.checkState(!this.deleted, "File already deleted");
        return this.file;
    }

    @Override
    public void close() throws IOException {
        if (this.deleted) {
            return;
        }
        this.deleted = true;
        deleteFile(this.file);
    }

    private void deleteFile(File f) throws IOException {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) {
                for (File kid : kids) {
                    deleteFile(kid);
                }
            }
        }
        if (f.exists()) {
            try {
                Files.delete(f.toPath());
            } catch (NoSuchFileException e) {
                // 已删除则忽略
            } catch (IOException e) {
                if (!f.delete()) {
                    throw new IOException("Failed to delete '" + f.getAbsolutePath() + "'", e);
                }
            }
        }
    }
}
```

编译这段源码，把 `.class` 文件替换回 Gradle 缓存中的 `instrumented-apkzlib-*.jar` 即可。
