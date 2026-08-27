import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    kotlin("android")

    kotlin("plugin.compose")
    kotlin("plugin.serialization") version "2.2.21"
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
}

// 从本地 keystore.properties 读取 release 签名密钥（已 gitignore，不入库）。
// 若文件不存在（如 CI 环境）则跳过，release 产出 unsigned 包。
val keystorePropertiesFile = file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

// gitVersionName 从 git tag 动态解析，彻底解决“发版时手改 build.gradle.kts 与 tag 双向不同步”问题。
// 规则：
//   1. 若当前 commit 刚好有 tag（如 v1.7.0 或 v1.7.0-rc1），直接提取为 "1.7.0" 或 "1.7.0-rc1"；
//   2. 若当前 commit 比上个 tag 多了 N 个提交（如 v1.7.0-2-g04bc2fa），提取为 "1.7.0-dev.2+g04bc2fa"；
//   3. 若无 git 环境或报错，fallback 到默认版本号 "1.7.0-dev"。
fun gitVersionName(): String = try {
    val process = Runtime.getRuntime().exec(
        arrayOf("git", "describe", "--tags", "--always", "--dirty"),
        null,
        rootProject.projectDir
    )
    process.waitFor()
    val raw = process.inputStream.bufferedReader().readText().trim()
    if (raw.startsWith("v")) {
        val version = raw.substring(1) // 去掉开头的 'v'
        // 如 "1.7.0"、"1.7.0-rc1" 或 "1.7.0-2-g04bc2fa"
        // 将 git describe 格式 "1.7.0-2-g04bc2fa" 转为规范语义化版本 "1.7.0-dev.2+g04bc2fa"
        val devRegex = Regex("""^(\d+\.\d+\.\d+(?:-[a-zA-Z0-9]+)?)-(\d+)-g([0-9a-f]+)(.*)$""")
        val match = devRegex.matchEntire(version)
        if (match != null) {
            val (base, count, hash, dirty) = match.destructured
            "$base-dev.$count+$hash$dirty"
        } else {
            version
        }
    } else if (raw.isNotEmpty()) {
        "1.7.0-dev+$raw"
    } else {
        "1.7.0-dev"
    }
} catch (e: Exception) {
    "1.7.0-dev"
}

// versionCode 从 git 提交数自动生成：随每次提交单调递增，无需手动维护，
// 杜绝"升 versionName 忘升 versionCode"导致升级判定失效。
// 工作目录用 rootProject.projectDir（仓库根），无 git 环境（如下载 zip 构建）时 fallback 到 1。
// CI 额外校验 versionCode 单调（见 .github/workflows/android-release.yml），防 rebase/squash 改写历史导致回退。
fun gitCommitCount(): Int = try {
    val process = Runtime.getRuntime().exec(
        arrayOf("git", "rev-list", "--count", "HEAD"),
        null,
        rootProject.projectDir
    )
    process.waitFor()
    process.inputStream.bufferedReader().readText().trim().toIntOrNull() ?: 1
} catch (e: Exception) {
    1
}

android {
    namespace = "com.aicode"
    compileSdk = 36
    buildToolsVersion = "35.0.0"

    signingConfigs {
        create("release") {
            if (keystorePropertiesFile.exists()) {
                storeFile = file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    defaultConfig {
        applicationId = "com.aicode"
        minSdk = 26
        // 锁定 targetSdk 28：Android 10+（API 29+）的 W^X/SELinux 策略禁止执行 App 可写
        // 数据目录里的文件，PRoot 二进制将无法运行（同 Termux 的取舍）。代价：不能上 Google Play。
        targetSdk = 28
        versionCode = gitCommitCount()
        versionName = gitVersionName()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    // 按容器镜像拆包：universal 同时含 arm + x86 两套 Alpine rootfs/proot（兼容所有设备但体积大），
    // armsolo 仅含 arm（对应 arm64-v8a），x86solo 仅含 x86（对应 x86_64）
    // ——单架构包体积约为通用包的一半。assets/container/... 由各 flavor 的 sourceSet 提供，
    // ContainerInstaller.ASSET_DIR 在 universal 下按设备 ABI 选其一，在 solo 包里只剩一套故一定命中。
    //
    // 注意：defaultConfig 不再固定 abiFilters，改由各 flavor 维度决定；universal 默认含全部
    // 依赖的 ABI（arm64-v8a + x86_64），单架构包各自收敛到单一 ABI，避免错架构设备加载错误的镜像。
    flavorDimensions += "container"
    productFlavors {
        create("universal") {
            dimension = "container"
            ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        }
        create("armsolo") {
            dimension = "container"
            ndk { abiFilters += "arm64-v8a" }
        }
        create("x86solo") {
            dimension = "container"
            ndk { abiFilters += "x86_64" }
        }
    }

    // 容器镜像按 flavor 共享 sourceSet：
    //   _armAssets 仅物理一份 arm 镜像，由 universal + armsolo 共享
    //   _x86Assets 仅物理一份 x86 镜像，由 universal + x86solo 共享
    // 这样单架构包天然只含一套镜像（AGP 资源并集合并：未被引用的目录不参与），
    // universal 含两套；镜像二进制在仓库里也只各一份，无重复。
    // 放弃 ignoreAssetsPattern=dir:x86：实测对 container/x86 整树无效（rc1 已证实）。
    sourceSets {
        getByName("universal") { assets.srcDir("src/_armAssets") }
        getByName("universal") { assets.srcDir("src/_x86Assets") }
        getByName("armsolo") { assets.srcDir("src/_armAssets") }
        getByName("x86solo") { assets.srcDir("src/_x86Assets") }
    }

    buildTypes {
        // debug 加包名后缀 .debug → applicationId 变 com.aicode.debug，与 release（com.aicode）
        // 可同机共存、互不覆盖。IDE 跑 debug 不再因签名不同卸载已装的正式版。
        // 注意：因 applicationId 不同，debug 变体私有目录为 /data/data/com.aicode.debug/，
        // release 已解压的容器 rootfs 与工作区项目在 debug 下不可见（需重新解压/clone），属预期隔离行为。
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 可复现构建：AGP 8.3+ 默认往 APK 写 version-control-info.textproto，
            // 其中 local_root_path 是构建机绝对路径，GitHub CI 与 F-Droid 构建服务器必然不同，
            // 会让两边 APK 字节不一致导致可复现对比失败。关闭之（版本溯源由 git tag/CI 保证）。
            vcsInfo {
                include = false
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }



    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/LICENSE"
            excludes += "META-INF/LICENSE.txt"
            excludes += "META-INF/license.txt"
            excludes += "META-INF/NOTICE"
            excludes += "META-INF/NOTICE.txt"
            excludes += "META-INF/notice.txt"
            excludes += "META-INF/INDEX.LIST"
            excludes += "/sshj.properties"
            excludes += "/kotlin-tooling-metadata.json"
            excludes += "/DebugProbesKt.bin"
        }
    }

    // targetSdk 故意锁定 28（PRoot 需在 app 可写目录执行二进制，Android 10+ W^X 禁止），
    // 代价是不进 Google Play——故关闭该平台的过期 targetSdk 检查。
    // 同时关闭 release 构建的 lint 检查：本仓库只出 GitHub Release 不上 Play，
    // lintVital 在 R8/打包阶段额外吃 CPU 与内存（2 核 7GB runner 易 OOM），且其发现不阻塞发布。
    lint {
        disable += "ExpiredTargetSdkVersion"
        checkReleaseBuilds = false
        abortOnError = false
    }

    // 单测环境不 mock android.jar：让 Log 等调用返回默认值而非抛 "not mocked"，
    // 否则被测代码里偶发的 FileLogger 日志调用会让纯 JVM 单测崩溃。
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// 彻底禁用 lintVital<Flavor>Release 任务（三 flavor 各一个），
// 使其不进入 assembleRelease 的任务图——比 lint.checkReleaseBuilds=false 更省构建开销与内存。
// 仅在 release 任务图执行前禁用，避免影响开发期 debug lint。
gradle.projectsEvaluated {
    tasks.matching { it.name.startsWith("lintVital") }.configureEach { enabled = false }
}

dependencies {
    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2026.01.00")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.animation:animation")

    // Vico 图表（Token 统计趋势图）
    implementation("com.patrykandpatrick.vico:compose-m3:2.4.4")

    // Lifecycle + ViewModel
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-process:2.9.0")
    implementation("androidx.activity:activity-compose:1.10.1")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.9.0")

    // Hilt 依赖注入
    implementation("com.google.dagger:hilt-android:2.56.1")
    ksp("com.google.dagger:hilt-compiler:2.56.1")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

    // Room 数据库
    implementation("androidx.room:room-runtime:2.7.1")
    implementation("androidx.room:room-ktx:2.7.1")
    ksp("androidx.room:room-compiler:2.7.1")

    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.1.4")

    // 网络请求
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // HTML 解析与清洗 (用于 WebFetchTool)
    implementation("org.jsoup:jsoup:1.18.1")

    // 协程
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    // Kotlin 序列化
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")

    // YAML 解析 (用于 Skill Frontmatter)
    implementation("org.yaml:snakeyaml:2.2")

    // 远程同步 (SFTP/FTP) 与内置 FTP 服务端
    implementation("com.hierynomus:sshj:0.38.0")
    // BouncyCastle：sshj 0.38.0 用 X25519 密钥交换，Android 自带裁剪版 BC 不含该算法，
    // 需显式引入完整版并注册替换（见 AIEditorApp.registerBouncyCastle）。版本与 sshj 传递依赖一致。
    implementation("org.bouncycastle:bcprov-jdk18on:1.75")
    implementation("commons-net:commons-net:3.10.0")
    implementation("org.apache.ftpserver:ftpserver-core:1.2.0")
    implementation("org.slf4j:slf4j-simple:2.0.9")

    // 容器：解压 Alpine rootfs tar.gz（正确处理 symlink/hardlink/权限位）
    implementation("org.apache.commons:commons-compress:1.26.2")
    // xz 解压支持：commons-compress 的 XZCompressorInputStream 依赖此库（解压用户导入的 .tar.xz 镜像）
    implementation("org.tukaani:xz:1.10")

    // Termux 开源终端组件：terminal-emulator 负责 VT100/ANSI 解析与 PTY（自带 native .so），
    // terminal-view 是渲染用的 Android View。经 JitPack 分发（com.github.<user>.<repo> 坐标形式），
    // 避免自行实现终端模拟器。
    implementation(project(":terminal-emulator"))
    implementation(project(":terminal-view"))

    // Material Icons
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")

    // Lucide Icons
    implementation("br.com.devsrsouza.compose.icons:feather:1.1.1")

    // 可拖拽排序列表（长按拖拽手势，提供商排序用）
    implementation("sh.calvin.reorderable:reorderable:3.1.0")

    // Markdown Renderer
    implementation("com.mikepenz:multiplatform-markdown-renderer-m3:0.41.0")
    // Markdown Renderer — Code Syntax Highlighting
    implementation("com.mikepenz:multiplatform-markdown-renderer-code:0.41.0")
    // 语法高亮引擎（markdown-renderer-code 传递引入，显式声明以供 diff 视图直接使用）
    implementation("dev.snipme:highlights-jvm:1.1.0")

    // WorkManager — 保活兜底：周期检查 TerminalKeepaliveService 存活并拉起（KeepaliveWorker）
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.hilt:hilt-work:1.2.0")

    // Core Android
    implementation("androidx.core:core:1.16.0")
    // AppCompat — 提供 AppCompatDelegate.setApplicationLocales 实现 per-app 语言切换
    implementation("androidx.appcompat:appcompat:1.7.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}
