package com.aicode.feature.settings.data.remote

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import androidx.core.content.pm.PackageInfoCompat
import com.aicode.core.util.compareVersions
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** 更新包自检结果。 */
sealed interface ApkVerifyResult {
    /** 通过：包名与签名都与已装 App 一致，且版本高于已装版本。 */
    data class Ok(val versionCode: Long) : ApkVerifyResult

    /** 不是可解析的安装包（下载到 HTML 错误页、文件被截断等）。 */
    data object NotAnApk : ApkVerifyResult

    /** 包名不符：镜像返回了别的应用的包。 */
    data class PackageMismatch(val actual: String) : ApkVerifyResult

    /** 签名证书与已装 App 不一致（改包、F-Droid 版、本地无 keystore 的包等）。 */
    data object SignatureMismatch : ApkVerifyResult

    /** 版本不高于已装 App（versionCode 更低，或 versionCode 相同且版本名不更高）。 */
    data class NotNewer(val versionCode: Long) : ApkVerifyResult
}

/**
 * 更新包自检：**包名 → 签名 → 版本** 三项都过才允许交给系统安装器。
 *
 * 签名一致是安全锚：更新包可能来自第三方镜像，sha256 摘要只证明「传输过程中没被改」，
 * 而签名一致才证明「这就是同一个开发者签的包」。签名不符时一律拒绝安装，引导用户改用
 * 浏览器手动下载——这同时覆盖了 F-Droid 版、beta/debug 变体、本地无 keystore 的 release 包。
 */
@Singleton
class ApkVerifier @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    fun verify(apk: File): ApkVerifyResult {
        val pm = context.packageManager
        val archive = pm.getPackageArchiveInfo(apk.absolutePath, signingFlags())
            ?: return ApkVerifyResult.NotAnApk

        if (archive.packageName != context.packageName) {
            return ApkVerifyResult.PackageMismatch(archive.packageName.orEmpty())
        }

        val installed = runCatching {
            pm.getPackageInfo(context.packageName, signingFlags())
        }.getOrNull() ?: return ApkVerifyResult.NotAnApk

        // 用「包含」而非「相等」：已装 App 侧取签名轮转历史，待装包侧取本版实际签名者，
        // 开发者做过签名轮转后仍能应用内更新。
        val installedSigners = signerDigests(installed, installedSide = true)
        val archiveSigners = signerDigests(archive, installedSide = false)
        if (archiveSigners.isEmpty() || !installedSigners.containsAll(archiveSigners)) {
            return ApkVerifyResult.SignatureMismatch
        }

        val archiveCode = PackageInfoCompat.getLongVersionCode(archive)
        val installedCode = PackageInfoCompat.getLongVersionCode(installed)
        return if (isNewerInstall(archiveCode, archive.versionName, installedCode, installed.versionName)) {
            ApkVerifyResult.Ok(archiveCode)
        } else {
            ApkVerifyResult.NotNewer(archiveCode)
        }
    }

    /**
     * API 28 起用 `GET_SIGNING_CERTIFICATES`（含签名轮转信息），26/27 只有旧字段；
     * 显式分支而不是直接传常量，避免在低版本上依赖未知标志位的行为。
     */
    private fun signingFlags(): Int =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }

    /**
     * 取签名证书的 SHA-256 摘要集合。
     *
     * API 28+ 用 [android.content.pm.SigningInfo]：已装 App 取 `signingCertificateHistory`
     * （含签名轮转的历史证书），待装包取 `apkContentsSigners`（这一版实际用什么签的）。
     * API 26/27 没有 SigningInfo，只有旧的 `signatures` 字段。
     */
    private fun signerDigests(info: PackageInfo, installedSide: Boolean): Set<String> {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            val signing = info.signingInfo ?: return emptySet()
            val signers = (if (installedSide) signing.signingCertificateHistory else signing.apkContentsSigners)
                .orEmpty()
            return signers.map { sha256(it.toByteArray()) }.toSet()
        }
        @Suppress("DEPRECATION")
        val legacy = info.signatures.orEmpty()
        return legacy.map { sha256(it.toByteArray()) }.toSet()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

/**
 * 更新包是否值得安装：versionCode 更高，或 versionCode 相同但 versionName 更高。
 *
 * 同一 commit 上先打 rc、后打正式 tag 时两者 versionCode 相等（versionCode = git 提交数），
 * 只比 versionCode 会把「rc 升正式版」误判成不更新。版本名缺失时无法证明更新，按不更新处理。
 */
internal fun isNewerInstall(
    archiveCode: Long,
    archiveVersionName: String?,
    installedCode: Long,
    installedVersionName: String?
): Boolean {
    if (archiveCode > installedCode) return true
    if (archiveCode < installedCode) return false
    if (archiveVersionName.isNullOrBlank() || installedVersionName.isNullOrBlank()) return false
    return compareVersions(archiveVersionName, installedVersionName) > 0
}
