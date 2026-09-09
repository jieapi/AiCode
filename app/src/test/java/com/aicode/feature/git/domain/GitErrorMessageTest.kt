package com.aicode.feature.git.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * git 失败输出的友好文案映射（[GitErrorMessage.friendly]）。
 * 每个模式匹配分支一个用例，入参尽量贴近真实 git stderr 文案，
 * 未命中时原样返回 raw（保留排查信息）。
 */
class GitErrorMessageTest {

    // ── 删除/切换分支 ───────────────────────────────────────────

    @Test
    fun cannotDeleteBranch_checkedOut_returnsSwitchHint() {
        val raw = "error: Cannot delete branch 'main' checked out at '/root/workspace'"
        assertEquals("无法删除当前所在分支，请先切换到其他分支", GitErrorMessage.friendly(raw))
    }

    @Test
    fun branchNotFullyMerged_returnsSafeDeleteHint() {
        val raw = "error: The branch 'feat/old' is not fully merged.\n" +
            "If you are sure you want to delete it, run 'git branch -D feat/old'."
        assertEquals("该分支有未合并的提交，无法安全删除", GitErrorMessage.friendly(raw))
    }

    @Test
    fun invalidReference_returnsNotFoundHint() {
        val raw = "fatal: invalid reference: nope"
        assertEquals("分支或引用不存在", GitErrorMessage.friendly(raw))
    }

    @Test
    fun localChangesWouldBeOverwritten_returnsStashHint() {
        val raw = "error: Your local changes to the following files would be overwritten by checkout:\n" +
            "\tapp/src/Main.kt\nPlease commit your changes or stash them before you switch branches."
        assertEquals("本地有未提交的改动会被覆盖，请先提交或暂存后再切换", GitErrorMessage.friendly(raw))
    }

    // ── 远程不可用 / 无权限 ─────────────────────────────────────

    @Test
    fun notGitRepository_returnsRemoteUnavailable() {
        val raw = "fatal: 'origin' does not appear to be a git repository"
        assertEquals("远程仓库不可用或无访问权限", GitErrorMessage.friendly(raw))
    }

    @Test
    fun couldNotReadFromRemote_returnsRemoteUnavailable() {
        val raw = "fatal: Could not read from remote repository.\n\nPlease make sure you have the correct access rights and the repository exists."
        assertEquals("远程仓库不可用或无访问权限", GitErrorMessage.friendly(raw))
    }

    // ── 鉴权失败 ────────────────────────────────────────────────

    @Test
    fun authenticationFailed_returnsCredentialHint() {
        val raw = "fatal: Authentication failed for 'https://github.com/user/repo.git/'"
        assertEquals("远程鉴权失败，请检查凭据配置（用户名/密码/Token）", GitErrorMessage.friendly(raw))
    }

    @Test
    fun invalidUsernameOrToken_returnsCredentialHint() {
        val raw = "fatal: Invalid username or token."
        assertEquals("远程鉴权失败，请检查凭据配置（用户名/密码/Token）", GitErrorMessage.friendly(raw))
    }

    @Test
    fun permissionDenied_returnsCredentialHint() {
        // 注意：真实场景通常紧跟 "Could not read from remote repository"，但该文案会先命中
        // 更靠前的「远程不可用」分支（friendly 按顺序匹配），故此处仅保留 Permission denied 本身。
        val raw = "git@github.com: Permission denied (publickey)."
        assertEquals("远程鉴权失败，请检查凭据配置（用户名/密码/Token）", GitErrorMessage.friendly(raw))
    }

    @Test
    fun passwordAuthenticationNotSupported_returnsCredentialHint() {
        val raw = "remote: Password authentication is not supported for GitHub."
        assertEquals("远程鉴权失败，请检查凭据配置（用户名/密码/Token）", GitErrorMessage.friendly(raw))
    }

    // ── 仓库不存在 ──────────────────────────────────────────────

    @Test
    fun repositoryNotFound_returnsRepoMissingHint() {
        val raw = "remote: Repository not found.\nfatal: repository 'https://github.com/user/nope.git/' not found"
        assertEquals("远程仓库不存在或无访问权限", GitErrorMessage.friendly(raw))
    }

    @Test
    fun repositoryAndNotFoundCombined_returnsRepoMissingHint() {
        val raw = "fatal: repository 'https://git.example.com/team/deleted.git' not found"
        assertEquals("远程仓库不存在或无访问权限", GitErrorMessage.friendly(raw))
    }

    // ── 拉取 / 推送 ─────────────────────────────────────────────

    @Test
    fun noTrackingInformation_returnsPullHint() {
        val raw = "There is no tracking information for the current branch.\n" +
            "Please specify which branch you want to rebase against."
        assertEquals("当前分支未关联远程分支，无法拉取", GitErrorMessage.friendly(raw))
    }

    @Test
    fun noConfiguredPushDestination_returnsRemoteHint() {
        val raw = "fatal: No configured push destination.\n" +
            "Either specify the URL from the command-line or configure a remote repository using\n" +
            "\n    git remote add <name> <url>\n\nand then push using the remote name"
        assertEquals("未配置远程仓库，无法推送", GitErrorMessage.friendly(raw))
    }

    @Test
    fun nonFastForward_returnsPullFirstHint() {
        val raw = "To https://github.com/user/repo.git\n" +
            " ! [rejected]        main -> main (non-fast-forward)\n" +
            "error: failed to push some refs to 'https://github.com/user/repo.git'"
        assertEquals("推送被拒绝，远程有更新的提交，请先拉取", GitErrorMessage.friendly(raw))
    }

    @Test
    fun rejectedFetchFirst_returnsPullFirstHint() {
        val raw = " ! [rejected]        main -> main (fetch first)"
        assertEquals("推送被拒绝，远程有更新的提交，请先拉取", GitErrorMessage.friendly(raw))
    }

    @Test
    fun updatesRejectedRemoteContains_returnsPullFirstHint() {
        val raw = "hint: Updates were rejected because the remote contains work that you do not\n" +
            "hint: have locally. This is usually caused by another repository pushing\n" +
            "hint: to the same ref."
        assertEquals("推送被拒绝，远程有更新的提交，请先拉取", GitErrorMessage.friendly(raw))
    }

    // ── 提交署名 ────────────────────────────────────────────────

    @Test
    fun pleaseTellMeWhoYouAre_returnsIdentityHint() {
        val raw = "*** Please tell me who you are.\n\nRun\n\n  git config --global user.email \"you@example.com\"\n  git config --global user.name \"Your Name\""
        assertEquals("尚未配置提交署名，请在设置中填写用户名和邮箱", GitErrorMessage.friendly(raw))
    }

    @Test
    fun authorIdentityUnknown_returnsIdentityHint() {
        val raw = "fatal: Author identity unknown"
        assertEquals("尚未配置提交署名，请在设置中填写用户名和邮箱", GitErrorMessage.friendly(raw))
    }

    // ── 未命中 / 空输入 ─────────────────────────────────────────

    @Test
    fun noMatch_returnsRaw() {
        val raw = "error: pathspec 'missing.txt' did not match any file(s) known to git"
        assertEquals(raw, GitErrorMessage.friendly(raw))
    }

    @Test
    fun emptyInput_returnsEmpty() {
        assertEquals("", GitErrorMessage.friendly(""))
    }

    @Test
    fun blankInput_returnsRawAsIs() {
        val raw = "  \n\t "
        assertEquals(raw, GitErrorMessage.friendly(raw))
    }
}