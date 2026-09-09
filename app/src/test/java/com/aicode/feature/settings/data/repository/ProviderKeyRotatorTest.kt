package com.aicode.feature.settings.data.repository

import com.aicode.feature.settings.domain.model.AIProviderConfig
import com.aicode.feature.settings.domain.model.KeyRotationStrategy
import com.aicode.feature.settings.domain.model.ProviderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ProviderKeyRotatorTest {

    private fun config(
        id: String = "provider-1",
        apiKey: String = "key-1",
        apiKeys: List<String> = emptyList(),
        multiKeyEnabled: Boolean = false,
        keyRotationStrategy: KeyRotationStrategy = KeyRotationStrategy.SEQUENTIAL,
        keyFailoverThreshold: Int = 2,
        keyCooldownMinutes: Int = 5
    ) = AIProviderConfig(
        id = id,
        name = "测试提供商",
        type = ProviderType.OPENAI,
        apiKey = apiKey,
        baseUrl = "https://example.com",
        defaultModel = "gpt-test",
        multiKeyEnabled = multiKeyEnabled,
        apiKeys = apiKeys,
        keyRotationStrategy = keyRotationStrategy,
        keyFailoverThreshold = keyFailoverThreshold,
        keyCooldownMinutes = keyCooldownMinutes
    )

    /** 多 Key 模式：effectiveApiKeys 取 apiKeys（去空白后非空者）。 */
    private fun multiKeyConfig(
        keys: List<String>,
        strategy: KeyRotationStrategy = KeyRotationStrategy.SEQUENTIAL,
        threshold: Int = 2,
        cooldownMinutes: Int = 5,
        id: String = "provider-1"
    ) = config(
        id = id,
        apiKey = "unused",
        apiKeys = keys,
        multiKeyEnabled = true,
        keyRotationStrategy = strategy,
        keyFailoverThreshold = threshold,
        keyCooldownMinutes = cooldownMinutes
    )

    // ---------- activeKey ----------

    /** 单 Key 直接返回，无需任何选择逻辑。 */
    @Test
    fun activeKey_singleKey_returnsIt() {
        val rotator = ProviderKeyRotator()

        assertEquals("key-1", rotator.activeKey(config(), "session-1"))
    }

    /** 没有任何可用 Key（单 Key 为空、多 Key 未配置）时返回 null。 */
    @Test
    fun activeKey_emptyKeys_returnsNull() {
        val rotator = ProviderKeyRotator()

        assertNull(rotator.activeKey(config(apiKey = ""), "session-1"))
    }

    /** 顺序策略：同一会话多次调用粘住同一个 Key（服务端 prompt 缓存按 Key 隔离）。 */
    @Test
    fun activeKey_sequential_sameSessionIsSticky() {
        val rotator = ProviderKeyRotator()
        val cfg = multiKeyConfig(keys = listOf("key-a", "key-b"))

        val first = rotator.activeKey(cfg, "session-1")
        repeat(5) {
            assertEquals(first, rotator.activeKey(cfg, "session-1"))
        }
    }

    /** 顺序策略：不同会话都从第一个未冷却 Key 开始。 */
    @Test
    fun activeKey_sequential_newSessionStartsFromFirst() {
        val rotator = ProviderKeyRotator()
        val cfg = multiKeyConfig(keys = listOf("key-a", "key-b"))

        assertEquals("key-a", rotator.activeKey(cfg, "session-1"))
        assertEquals("key-a", rotator.activeKey(cfg, "session-2"))
        assertEquals("key-a", rotator.activeKey(cfg, "session-3"))
    }

    /** 轮询策略：新会话轮流取起始 Key。 */
    @Test
    fun activeKey_roundRobin_rotatesStartingKeyPerNewSession() {
        val rotator = ProviderKeyRotator()
        val cfg = multiKeyConfig(
            keys = listOf("key-a", "key-b", "key-c"),
            strategy = KeyRotationStrategy.ROUND_ROBIN
        )

        assertEquals("key-a", rotator.activeKey(cfg, "session-1"))
        assertEquals("key-b", rotator.activeKey(cfg, "session-2"))
        assertEquals("key-c", rotator.activeKey(cfg, "session-3"))
        assertEquals("key-a", rotator.activeKey(cfg, "session-4"))
    }

    /** 轮询策略下同一会话仍粘住同一个 Key，不随游标漂移。 */
    @Test
    fun activeKey_roundRobin_sameSessionStaysSticky() {
        val rotator = ProviderKeyRotator()
        val cfg = multiKeyConfig(
            keys = listOf("key-a", "key-b"),
            strategy = KeyRotationStrategy.ROUND_ROBIN
        )

        val first = rotator.activeKey(cfg, "session-1")
        repeat(3) {
            assertEquals(first, rotator.activeKey(cfg, "session-1"))
        }
        // 其它会话不受影响，继续轮转
        assertEquals("key-b", rotator.activeKey(cfg, "session-2"))
    }

    /** sessionId 为 null 时不建绑定：轮询策略每次调用都推进游标。 */
    @Test
    fun activeKey_nullSession_roundRobinAdvancesEveryCall() {
        val rotator = ProviderKeyRotator()
        val cfg = multiKeyConfig(
            keys = listOf("key-a", "key-b"),
            strategy = KeyRotationStrategy.ROUND_ROBIN
        )

        assertEquals("key-a", rotator.activeKey(cfg, null))
        assertEquals("key-b", rotator.activeKey(cfg, null))
        assertEquals("key-a", rotator.activeKey(cfg, null))
    }

    /** 冷却中的 Key 不被新会话选中。 */
    @Test
    fun activeKey_skipsCoolingDownKey() {
        val rotator = ProviderKeyRotator()
        val cfg = multiKeyConfig(keys = listOf("key-a", "key-b"), cooldownMinutes = 60)

        // 先建立配置快照并选中 key-a，随后 key-a 连续失败达阈值进入冷却
        rotator.activeKey(cfg, "session-1")
        rotator.reportFailure("provider-1", "session-1", "key-a")
        rotator.reportFailure("provider-1", "session-1", "key-a")

        // 新会话不能再选冷却中的 key-a
        assertEquals("key-b", rotator.activeKey(cfg, "session-2"))
    }

    /** 全部 Key 都在冷却时退回最早到期的那个，宁可重试也不返回 null。 */
    @Test
    fun activeKey_allCooling_fallsBackToSoonestExpiry() {
        val rotator = ProviderKeyRotator()
        val cfg = multiKeyConfig(keys = listOf("key-a", "key-b"), cooldownMinutes = 60)

        // key-a 先冷却
        rotator.activeKey(cfg, "s1")
        rotator.reportFailure("provider-1", "s1", "key-a")
        rotator.reportFailure("provider-1", "s1", "key-a")
        // key-b 后冷却
        rotator.activeKey(cfg, "s2")
        rotator.reportFailure("provider-1", "s2", "key-b")
        rotator.reportFailure("provider-1", "s2", "key-b")

        // key-a 冷却截止更早，全冷却时选中它
        assertEquals("key-a", rotator.activeKey(cfg, "s3"))
    }

    // ---------- currentKey ----------

    /** currentKey 返回最近一次 activeKey 选中的 Key。 */
    @Test
    fun currentKey_returnsLastSelectedKey() {
        val rotator = ProviderKeyRotator()
        val cfg = multiKeyConfig(keys = listOf("key-a", "key-b"))

        rotator.activeKey(cfg, "session-1") // 选中 key-a

        assertEquals("key-a", rotator.currentKey(cfg))
    }

    /** currentKey 不推进轮询游标：只读调用后第一个新会话仍从原游标位置起步。 */
    @Test
    fun currentKey_doesNotAdvanceRoundRobinCursor() {
        val rotator = ProviderKeyRotator()
        val cfg = multiKeyConfig(
            keys = listOf("key-a", "key-b"),
            strategy = KeyRotationStrategy.ROUND_ROBIN
        )

        // 只调用 currentKey（不调用 activeKey）
        rotator.currentKey(cfg)
        rotator.currentKey(cfg)

        // 游标未被推进，第一个新会话仍从 key-a 开始
        assertEquals("key-a", rotator.activeKey(cfg, "session-1"))
    }

    /** 冷启动（无任何历史调用）时，currentKey 返回第一个未冷却 Key。 */
    @Test
    fun currentKey_noSelection_returnsFirstUsableKey() {
        val rotator = ProviderKeyRotator()
        val cfg = multiKeyConfig(keys = listOf("key-a", "key-b"))

        // currentKey 不依赖 activeKey 建立的配置快照，直接可用
        assertEquals("key-a", rotator.currentKey(cfg))
    }

    /** lastSelected 冷却时跳过它；全部冷却则退回列表第一个。 */
    @Test
    fun currentKey_allCooling_fallsBackToFirstKey() {
        val rotator = ProviderKeyRotator()
        val cfg = multiKeyConfig(keys = listOf("key-a", "key-b"), cooldownMinutes = 60)

        rotator.activeKey(cfg, "s1") // key-a，lastSelected=key-a
        rotator.reportFailure("provider-1", "s1", "key-a")
        rotator.reportFailure("provider-1", "s1", "key-a") // key-a 冷却，切到 key-b
        rotator.reportFailure("provider-1", "s1", "key-b")
        rotator.reportFailure("provider-1", "s1", "key-b") // key-b 冷却，切回冷却中的 key-a

        // lastSelected=key-a 在冷却 → 跳过；两个 Key 都冷却 → 退回列表第一个
        assertEquals("key-a", rotator.currentKey(cfg))
    }

    // ---------- reportFailure ----------

    /** 未达阈值时返回 null 并累计失败计数。 */
    @Test
    fun reportFailure_belowThreshold_returnsNull() {
        val rotator = ProviderKeyRotator()
        val cfg = multiKeyConfig(keys = listOf("key-a", "key-b"), threshold = 3)

        rotator.activeKey(cfg, "session-1") // 建立配置快照

        assertNull(rotator.reportFailure("provider-1", "session-1", "key-a"))
        assertNull(rotator.reportFailure("provider-1", "session-1", "key-a"))
    }

    /** 连续失败达阈值：返回切换结果并重绑会话到新 Key。 */
    @Test
    fun reportFailure_reachesThreshold_switchesKey() {
        val rotator = ProviderKeyRotator()
        val cfg = multiKeyConfig(keys = listOf("key-a", "key-b"))

        rotator.activeKey(cfg, "session-1") // key-a

        assertNull(rotator.reportFailure("provider-1", "session-1", "key-a")) // 第 1 次
        val switched = rotator.reportFailure("provider-1", "session-1", "key-a") // 第 2 次达阈值

        assertNotNull(switched)
        assertEquals("key-b", switched!!.newKey)
        assertEquals(2, switched.newIndex)
        assertEquals(2, switched.total)
        // 会话重绑到新 Key
        assertEquals("key-b", rotator.activeKey(cfg, "session-1"))
    }

    /** 从未建立配置快照（没调用过 activeKey）时直接返回 null。 */
    @Test
    fun reportFailure_withoutPriorSetup_returnsNull() {
        val rotator = ProviderKeyRotator()
        val cfg = multiKeyConfig(keys = listOf("key-a", "key-b"))

        assertNull(rotator.reportFailure("provider-1", "session-1", "key-a"))
    }

    /** 单 Key 配置无可切换目标：返回 null。 */
    @Test
    fun reportFailure_singleKey_returnsNull() {
        val rotator = ProviderKeyRotator()
        val cfg = config()

        rotator.activeKey(cfg, "session-1")

        assertNull(rotator.reportFailure("provider-1", "session-1", "key-1"))
        assertNull(rotator.reportFailure("provider-1", "session-1", "key-1"))
    }

    /** 上报不属于该 provider 的 Key：返回 null。 */
    @Test
    fun reportFailure_unknownKey_returnsNull() {
        val rotator = ProviderKeyRotator()
        val cfg = multiKeyConfig(keys = listOf("key-a", "key-b"))

        rotator.activeKey(cfg, "session-1")

        assertNull(rotator.reportFailure("provider-1", "session-1", "key-unknown"))
    }

    /** 成功后清零失败计数：清零后需重新累计到阈值才切换。 */
    @Test
    fun reportSuccess_resetsFailureCount() {
        val rotator = ProviderKeyRotator()
        val cfg = multiKeyConfig(keys = listOf("key-a", "key-b"), threshold = 3)

        rotator.activeKey(cfg, "session-1")

        rotator.reportFailure("provider-1", "session-1", "key-a") // 1/3
        rotator.reportFailure("provider-1", "session-1", "key-a") // 2/3
        rotator.reportSuccess("provider-1", "key-a")              // 清零
        rotator.reportFailure("provider-1", "session-1", "key-a") // 1/3

        assertNull(rotator.reportFailure("provider-1", "session-1", "key-a")) // 2/3
        val switched = rotator.reportFailure("provider-1", "session-1", "key-a") // 3/3 达阈值
        assertNotNull(switched)
        assertEquals("key-b", switched!!.newKey)
    }

    /** 失败切换后，原 Key 进入冷却（冷却时长非零时）。 */
    @Test
    fun reportFailure_switchAppliesCooldown() {
        val rotator = ProviderKeyRotator()
        val cfg = multiKeyConfig(keys = listOf("key-a", "key-b"), cooldownMinutes = 60)

        rotator.activeKey(cfg, "session-1") // key-a
        rotator.reportFailure("provider-1", "session-1", "key-a")
        rotator.reportFailure("provider-1", "session-1", "key-a")

        // key-a 冷却中，新会话不再选它
        assertEquals("key-b", rotator.activeKey(cfg, "session-2"))
    }

    /** 冷却时长为 0 表示不冷却：切换后原 Key 仍可被新会话选中。 */
    @Test
    fun reportFailure_cooldownZero_noCooldownApplied() {
        val rotator = ProviderKeyRotator()
        val cfg = multiKeyConfig(keys = listOf("key-a", "key-b"), cooldownMinutes = 0)

        rotator.activeKey(cfg, "s1") // key-a
        rotator.reportFailure("provider-1", "s1", "key-a")
        rotator.reportFailure("provider-1", "s1", "key-a")

        // 无冷却，key-a 重新可用；顺序策略下新会话从第一个未冷却 Key 开始
        assertEquals("key-a", rotator.activeKey(cfg, "s2"))
    }

    /** 轮询策略下失败切换同样生效，且切换会继续推进轮询游标。 */
    @Test
    fun reportFailure_roundRobin_switchesAndAdvancesCursor() {
        val rotator = ProviderKeyRotator()
        val cfg = multiKeyConfig(
            keys = listOf("key-a", "key-b", "key-c"),
            strategy = KeyRotationStrategy.ROUND_ROBIN
        )

        rotator.activeKey(cfg, "s1") // key-a（游标 0→1）
        rotator.reportFailure("provider-1", "s1", "key-a")
        val switched = rotator.reportFailure("provider-1", "s1", "key-a")

        // 游标=1：候选 [b,c,a]，避开 key-a → key-b
        assertEquals("key-b", switched!!.newKey)
        // 游标被推进（1→2），下一个新会话从 key-c 起步
        assertEquals("key-c", rotator.activeKey(cfg, "s2"))
    }
}