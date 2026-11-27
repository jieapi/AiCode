package com.aicode.feature.agent.domain.container

import com.aicode.feature.settings.data.repository.ExecutionMode
import kotlinx.serialization.Serializable

/**
 * 一个可切换的容器配置：镜像来源 + shell 路径 + 额外 proot 绑定/参数。
 *
 * 内置 [BUILTIN_ALPINE] 描述现有 Alpine rootfs（来自 assets）。用户自定义 profile 通过导入
 * tar.gz 提供 rootfs。所有容器首次进入终端时统一弹出初始化菜单（见 assets/aicode/provision.sh），
 * 由用户选择自动安装基础工具或手动安装，装包失败不阻塞进入 shell，处理方式一致。
 *
 * 远程 SSH profile（[mode] == [ExecutionMode.REMOTE_SSH]）不导入本地 rootfs，而是绑定一个
 * 工作区已配置的 SSH 通道（[RootfsSource.RemoteSsh]），命令执行走 [RemoteSshEngine]。
 */
@Serializable
enum class ContainerRuntime {
    PROOT,
    TAWCROOT
}

@Serializable
data class ContainerProfile(
    val id: String,
    val name: String,
    val rootfsSource: RootfsSource,
    /** 自定义镜像用的 shell（如 /bin/sh 或 /bin/bash）；未指定时按 provision 状态自动选择。 */
    val shellPath: String?,
    /** 额外 -b 绑定，如 ["/sdcard:/mnt/sdcard"]，逐项作为 `-b <binding>` 拼进 proot argv。 */
    val extraBindings: List<String> = emptyList(),
    /** 额外 proot 参数，原样追加到基础 argv（如 ["-k","..."]）。 */
    val extraArgs: List<String> = emptyList(),
    /** 自定义环境变量，注入容器内进程（覆盖同名默认值）。 */
    val env: Map<String, String> = emptyMap(),
    val isBuiltin: Boolean,
    /** 该 profile 的执行模式：本地 PRoot 容器 or 远程 SSH。选中时据此切全局 [ExecutionMode]。 */
    val mode: ExecutionMode = ExecutionMode.LOCAL_PROOT,
    /** 本地容器运行时（默认 PRoot；tawcroot 为 Android 10+ 实验性运行时）。 */
    val runtime: ContainerRuntime = ContainerRuntime.PROOT,
    /** 添加时间（毫秒时间戳），容器列表按此降序排列；旧数据/内置默认 0 排最后。 */
    val createdAt: Long = 0
) {
    companion object {
        const val BUILTIN_ID = "builtin-alpine"

        /**
         * 新容器默认注入的 proot 参数。刻意与用户手动添加走同一条路径（[extraArgs]，追加在 proot argv
         * 末尾），而不在引擎里硬编码 argv——硬编码的位置与手动添加不同，会导致 --link2symlink 行为异常，
         * 且无法在编辑页撤销。
         *  - --link2symlink：Android SELinux 禁止 app 域创建硬链接，dpkg 用 link() 备份文件会 Permission
         *    denied，proot 以符号链接模拟硬链接绕过；
         *  - --kill-on-exit：proot 退出时级联杀掉容器内子进程，避免终端仍在运行时重置容器残留孤儿进程致卡死。
         */
        val DEFAULT_PROOT_ARGS = listOf("--link2symlink", "--kill-on-exit")

        /** 内置 Alpine profile：镜像来自 assets，复用现有安装/provision 全流程。 */
        val BUILTIN_ALPINE = ContainerProfile(
            id = BUILTIN_ID,
            name = "内置 Alpine",
            rootfsSource = RootfsSource.Asset("alpine-rootfs.bin"),
            shellPath = null,
            extraArgs = DEFAULT_PROOT_ARGS,
            isBuiltin = true
        )
    }
}

@Serializable
sealed interface RootfsSource {
    /** 内置：assets 里的 rootfs 文件（[ContainerProfile.BUILTIN_ALPINE] 用）。 */
    @Serializable
    data class Asset(val path: String) : RootfsSource

    /** 用户导入的 tar.gz，经 content uri 引用，解压到 filesDir/rootfs_<id>。 */
    @Serializable
    data class LocalFile(val uri: String) : RootfsSource

    /** 远程 SSH：绑定工作区已配置的 SSH 通道（connectionId）+ 远程工作区路径，不导入本地 rootfs。 */
    @Serializable
    data class RemoteSsh(
        val connectionId: String,
        val remoteWorkspacePath: String
    ) : RootfsSource
}
