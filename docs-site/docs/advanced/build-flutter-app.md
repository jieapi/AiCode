# 在容器中编译 Flutter 应用

在 AiCode 的 Linux 容器（PRoot）中配置 JDK、Android SDK 与 Flutter SDK 后，即可直接从源码编译 Flutter 应用的 Android APK。本文以 Debian 12 (bookworm) aarch64 为例，已在 PRoot 容器内实测通过 debug APK 的构建。

::: tip 支持范围
ARM64 容器仅支持构建 **debug APK**（JIT 模式，原生支持 ARM64）。**release / profile 包无法在本地构建**：Google 仅为 Android AOT 发布 linux-x64 平台的 gen_snapshot，ARM64 宿主机上无法执行 AOT 编译。如需 release 包，请使用 GitHub Actions 等远程构建方式。
:::

::: tip 镜像与网络说明
本文默认使用国内镜像进行安装与构建（下载更快、更稳）。镜像产物与官方完全等价：即使网络可直接访问 `dl.google.com` 等官方源，也无需还原为官方配置。
:::

## 环境基线

| 项 | 值 |
| --- | --- |
| 系统 | Debian GNU/Linux 12 (bookworm) aarch64（PRoot） |
| JDK | OpenJDK 17（headless） |
| Flutter | 3.27.4 stable（git clone 指定 tag） |
| Dart | 3.6.2（linux-arm64） |
| Gradle | 项目 wrapper 自带（8.3），无需系统安装 |
| SDK | platforms android-35，build-tools 33.0.1 + 35.0.0，platform-tools |
| aapt2 | ARM64 静态编译版（lzhiyong/android-sdk-tools） |

## 1. 安装基础依赖

```bash
apt update
apt install -y openjdk-17-jdk-headless curl unzip zip git
```

验证 JDK：

```bash
java -version
# openjdk version "17..."
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

yes | sdkmanager --install "platforms;android-35" "build-tools;33.0.1" "build-tools;35.0.0" "platform-tools"
```

::: tip 版本说明
Flutter 3.27 模板的 `compileSdk` 为 35（需要 `platforms;android-35`），`buildToolsVersion` 默认值为 **33.0.1**（缺失时会报 `Failed to find Build Tools revision 33.0.1`）。build-tools 35.0.0 用于第 5 节的 aapt2 override，可选但建议安装。
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

yes | sdkmanager --install "platforms;android-35" "build-tools;33.0.1" "build-tools;35.0.0" "platform-tools"
```

### 备选：从镜像手工组装 SDK

若 `SDK_TEST_BASE_URL` 方式不可用，可从腾讯云镜像下载组件 zip 手工组装，最终得到 `build-tools/`、`platforms/`、`platform-tools/`、`cmdline-tools/` 四个目录，效果与 sdkmanager 一致：

| 组件 | 镜像地址 |
| --- | --- |
| commandlinetools | `https://mirrors.cloud.tencent.com/AndroidSDK/commandlinetools-linux-13114758_latest.zip` |
| platform-35 | `https://mirrors.cloud.tencent.com/AndroidSDK/platform-35_r02.zip` |
| build-tools 33.0.1 | `https://mirrors.cloud.tencent.com/AndroidSDK/build-tools_r33.0.1-linux.zip` |
| build-tools 35.0.0 | `https://mirrors.cloud.tencent.com/AndroidSDK/build-tools_r35_linux.zip` |
| platform-tools | `https://mirrors.cloud.tencent.com/AndroidSDK/platform-tools_r35.0.2-linux.zip` |

镜像下载大文件时易发生连接中断，建议使用 `curl -fL -C -` 断点续传并配合循环重试。目录组装：

```bash
cd ~/android/sdk
# cmdline-tools：解压出 cmdline-tools/，重命名为 latest
unzip clt.zip && mv cmdline-tools cmdline-tools/latest   # 实际解压布局以 zip 为准
# platform-35：解压出 android-35/，移入 platforms/
mkdir -p platforms && mv android-35 platforms/android-35
# build-tools：zip 解压出 android-13/（33.0.1）或 android-15/（35.0.0），移入对应版本目录
mkdir -p build-tools/33.0.1 build-tools/35.0.0
mv android-13/* build-tools/33.0.1/ && mv android-15/* build-tools/35.0.0/
# platform-tools：解压即得 platform-tools/，无需移动
```

## 3. 替换 ARM64 原生二进制

Google 官方的 `aapt2`、`aidl`、`zipalign`、`adb` 等工具为 x86_64 架构编译，无法在 aarch64 上执行（属架构限制，与网络无关），必须替换为社区提供的 ARM64 静态编译版。从 `https://github.com/lzhiyong/android-sdk-tools/releases/download/35.0.2/android-sdk-tools-static-aarch64.zip` 下载；下载缓慢或失败时，可在原链接前加 GitHub 代理前缀：`https://gh-proxy.com/https://github.com/...`

```bash
curl -L -O https://github.com/lzhiyong/android-sdk-tools/releases/download/35.0.2/android-sdk-tools-static-aarch64.zip
unzip android-sdk-tools-static-aarch64.zip -d ~/armtools35
# 覆盖 build-tools（33.0.1 与 35.0.0 两个目录）与 platform-tools
cp -p ~/armtools35/build-tools/* ~/android/sdk/build-tools/35.0.0/
cp -p ~/armtools35/build-tools/* ~/android/sdk/build-tools/33.0.1/
cp -p ~/armtools35/platform-tools/* ~/android/sdk/platform-tools/
chmod +x ~/android/sdk/build-tools/*/* ~/android/sdk/platform-tools/*
```

验证：

```bash
~/android/sdk/build-tools/35.0.0/aapt2 version   # 能输出版本号即正常
~/android/sdk/build-tools/35.0.0/zipalign        # 有 usage 输出即正常
```

## 4. 安装 Flutter SDK

使用 git 克隆指定 tag：

```bash
git clone --depth 1 --branch 3.27.4 https://github.com/flutter/flutter.git ~/.local/flutter-3.27.4
export PATH=$PATH:~/.local/flutter-3.27.4/bin

flutter --version   # 首次运行会自动下载 dart-sdk 与引擎产物
```

::: tip PRoot 下 dart-sdk 安装缓慢的处理
首次运行 `flutter --version` 会自动下载 dart-sdk（约 210MB）并解压。PRoot 环境下该步骤可能长时间无响应（下载慢、解压慢、锁等待）。如遇卡住，可手动完成安装以绕开自动引导：

```bash
cd ~/.local/flutter-3.27.4/bin/cache
EV=$(cat ../internal/engine.version)     # 如 82bd5b7209295a5b7ff8cae0df96e7870171e3a5
curl -fL -o dart-sdk-linux-arm64.zip \
  "https://storage.flutter-io.cn/flutter_infra_release/flutter/$EV/dart-sdk-linux-arm64.zip"
# 网络可达时亦可改用官方源：https://storage.googleapis.com/flutter_infra_release/flutter/$EV/dart-sdk-linux-arm64.zip
unzip -q dart-sdk-linux-arm64.zip -d .   # 解压出 dart-sdk/
rm -f dart-sdk-linux-arm64.zip
echo "$EV" > engine-dart-sdk.stamp       # 写入 stamp，flutter 将跳过自动下载
```

验证：

```bash
~/.local/flutter-3.27.4/bin/cache/dart-sdk/bin/dart --version
# Dart SDK version: 3.6.2 ... on "linux_arm64"
```
:::

## 5. 配置

### local.properties

`flutter create` 会自动生成（含 `sdk.dir` 与 `flutter.sdk`），无需手写，确认内容正确即可：

```bash
cat ~/workspace/<项目>/android/local.properties
# sdk.dir=/root/android/sdk
# flutter.sdk=/root/.local/flutter-3.27.4
```

### 全局 Gradle 配置

aapt2 覆盖配置应写入全局文件，不要写进项目的 `gradle.properties`：

```bash
mkdir -p ~/.gradle
echo "android.aapt2FromMavenOverride=/root/android/sdk/build-tools/35.0.0/aapt2" > ~/.gradle/gradle.properties
```

容器可用内存约 4G，项目 `android/gradle.properties` 默认 `-Xmx4G` 可能触发 OOM，建议调低：

```properties
org.gradle.jvmargs=-Xmx2G -XX:MaxMetaspaceSize=1G -XX:+HeapDumpOnOutOfMemoryError
```

### 镜像配置（默认）

gradle 解析依赖时若仓库列表包含 `google()`（指向 dl.google.com），网络不可达时会因连接超时长时间挂起。默认推荐直接使用镜像仓库（构建更快），即使网络可直连官方源也无需还原以下配置：

1. **Gradle wrapper 镜像**：项目 `android/gradle/wrapper/gradle-wrapper.properties` 的 `distributionUrl` 默认指向 `services.gradle.org`，可改为国内镜像：

```
distributionUrl=https\://mirrors.huaweicloud.com/gradle/gradle-8.3-all.zip
```

2. **依赖仓库镜像**：所有 repositories 移除 `google()` / `mavenCentral()` / `gradlePluginPortal()`，仅保留阿里云镜像：

```groovy
maven { url 'https://maven.aliyun.com/repository/google' }
maven { url 'https://maven.aliyun.com/repository/central' }
maven { url 'https://maven.aliyun.com/repository/gradle-plugin' }   // 插件仓库需要时添加
```

需要修改的位置共 5 处：

| 文件 | 说明 |
| --- | --- |
| 项目 `android/settings.gradle` | pluginManagement.repositories |
| 项目 `android/build.gradle` | allprojects.repositories |
| `flutter SDK/packages/flutter_tools/gradle/settings.gradle.kts` | :gradle 子项目仓库（不修改时 AGP 7.3.0 将连接 dl.google.com） |
| `flutter SDK/packages/flutter_tools/gradle/resolve_dependencies.gradle` | 依赖预解析 |
| `flutter SDK/packages/flutter_tools/gradle/src/main/kotlin/dependency_version_checker.gradle.kts` | 版本检查 buildscript |

::: warning 注意
flutter 引擎产物仓库（`download.flutter.io`）由 flutter 插件自动注入，其地址基于 `FLUTTER_STORAGE_BASE_URL` 拼接，请勿删除。上述 5 处仅需处理 google/mavenCentral 相关条目。SDK 内文件在 flutter 升级后会被覆盖，需重新修改。
:::

3. **引擎产物与依赖下载源**：构建时设置环境变量，使 flutter 的 dart-sdk、引擎产物与 pub 包改走中国镜像或官方源：

```bash
export FLUTTER_STORAGE_BASE_URL=https://storage.flutter-io.cn   # 引擎产物等走中国镜像
unset PUB_HOSTED_URL    # 使用官方 pub.dev（一般可直连）；pub.flutter-io.cn 曾出现 content-hash 校验失败的案例
```

## 6. 编译

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64
export PATH=$PATH:~/.local/flutter-3.27.4/bin
export FLUTTER_STORAGE_BASE_URL=https://storage.flutter-io.cn   # 引擎产物等走中国镜像（默认推荐）
unset PUB_HOSTED_URL    # 使用官方 pub.dev（默认推荐，可直连）

cd ~/workspace/<项目>
flutter build apk --debug --target-platform android-arm64
```

上方环境变量为默认推荐配置（第 5 节已说明），网络可达官方源时也可保留，无需还原。

首次构建约 3~10 分钟（下载 Gradle 发行版、依赖并编译，视网络而定），后续增量构建约 1~2 分钟。Gradle daemon 在 PRoot 下可正常工作；若 daemon 异常退出导致锁文件冲突，可加 `--no-daemon` 或删除 `~/.gradle/caches/*.lock`。

构建成功后 APK 输出：

```
build/app/outputs/flutter-apk/app-debug.apk
```

可使用 `aapt2 dump badging` 验证：

```bash
~/android/sdk/build-tools/35.0.0/aapt2 dump badging build/app/outputs/flutter-apk/app-debug.apk | head -3
```

## 常见问题

| 现象 | 原因 | 解决 |
| --- | --- | --- |
| `Connect to dl.google.com timed out`（长时间挂起） | 仓库包含 `google()`/`mavenCentral()` 且 dl.google.com 不可达 | 按第 5 节使用镜像仓库（默认配置） |
| `Could not resolve io.flutter:flutter_embedding_debug` | 同上，flutter 引擎依赖解析时访问 dl.google.com | 同上 |
| `Failed to find Build Tools revision 33.0.1` | Flutter 模板默认 buildTools 33.0.1，SDK 缺少该版本 | 补装 `build-tools;33.0.1`（并替换 ARM64 二进制） |
| `aapt2 ... cannot execute binary / syntax error` | 使用了 x86 版 aapt2 | 确认 `~/.gradle/gradle.properties` 的 `aapt2FromMavenOverride` 指向 ARM64 版 |
| flutter 引导下载 dart-sdk 长时间无响应 / 反复锁等待 | PRoot 下自动下载与解压不稳定 | 按第 4 节手动安装 dart-sdk 并写入 stamp |
| `Downloaded archive for xxx had wrong content-hash`（pub 反复重试） | pub 镜像返回损坏包 | `unset PUB_HOSTED_URL` 改用官方 pub.dev，并清空 `~/.pub-cache` |
| `flutter doctor` Android toolchain 检查超时 | doctor 内部通过 sdkmanager 联网验证 dl.google.com | dl.google.com 不可达时属正常现象，不影响构建 |
| Gradle 锁文件冲突 | 上次构建未正常退出 | 删除 `~/.gradle/caches/*.lock` 或加 `--no-daemon` |
| 构建命令接管道后看不到进度 | `\| tail` 缓冲输出 | 直接运行构建命令，勿接 tail；失败信息在前 1~3 分钟的依赖解析阶段即可见 |

## 限制与提示

- **release/profile 无法在 ARM64 构建**：Android AOT 的 gen_snapshot 仅有 linux-x64 版。如需 release 包，请使用 GitHub Actions（在 x64 上执行 `flutter build apk --release`）或其它远程构建方式。
- 模板 `flutter create` 的 `compileSdk=35`、`buildToolsVersion=33.0.1`，修改模板前请先确认对应 SDK 组件已安装。
- PRoot 下大量小文件的解压（如 dart-sdk、依赖缓存）速度偏慢属正常现象，请勿误判为卡死。
- 若依赖解析持续失败，可先单独 `curl -sI` 验证对应仓库 URL 的可达性，再检查仓库顺序。
