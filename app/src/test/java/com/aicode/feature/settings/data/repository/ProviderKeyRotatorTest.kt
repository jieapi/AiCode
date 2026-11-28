package com.aicode.feature.settings.data.repository

import com.aicode.feature.settings.domain.model.AIProviderConfig
import com.aicode.feature.settings.domain.model.KeyRotationStrategy
import com.aicode.feature.settings.domain.model.ProviderKey
import com.aicode.feature.settings.domain.model.ProviderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ProviderKeyRotatorTest {

    private fun config(
        id: String = "provider-1",
        apiKey: String = "key-1",
        apiKeys: List<ProviderKey> = emptyList(),
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
        apiKeys = keys.map { ProviderKey(it) },
        multiKeyEnabled = true,
        keyRotationStrategy = strategy,
        keyFailoverThreshold = threshold,
        keyCooldownMinutes = cooldownMinutes
    )

    /** 轮换状态按条目身份（值 + 覆盖地址）记录，上报失败时传的也是它。 */
    private fun entryId(value: String, baseUrl: String = "") = ProviderKey(value, baseUrl = baseUrl).id

    // ---------- activeKey ----------

    /** 单 Key 直接返回，无需任何选择逻辑。 */
    @Test
    fun activeKey_singleKey_returnsIt() {
        val rotator = ProviderKeyRotator()

        assertEquals("key-1", rotator.activeKey(config(), "session-1")?.value)
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

        val first = rotator.activeKey(cfg, "session-1")?.id
        repeat(5) {
            assertEquals(first, rotator.activeKey(cfg, "session-1")?.id)
        }
    }

    /** 顺序策略：不同会话都从第一个未冷却 Key 开始。 */
    @Test
    fun activeKey_sequential_newSessionStartsFromFirst() {
        val rotator = ProviderKeyRotator()
        val cfg = multiKeyConfig(keys = listOf("key-a", "key-b"))

        assertEquals("key-a", rotator.activeKey(cfg, "session-1")?.value)
        assertEquals("key-a", rotator.activeKey(cfg, "session-2")?.value)
        assertEquals("key-a", rotator.activeKey(cfg, "session-3")?.value)
    }

    /** 轮询策略：新会话轮流取起始 Key。 */
    @Test
    fun activeKey_roundRobin_rotatesStartingKeyPerNewSession() {
        val rotator = ProviderKeyRotator()
        val cfg = multiKeyConfig(
            keys = listOf("key-a", "key-b", "key-c"),
            strategy = KeyRotationStrategy.ROUND_ROBIN
        )

        assertEquals("key-a", rotator.activeKey(cfg, "session-1")?.value)
        assertEquals("key-b", rotator.activeKey(cfg, "session-2")?.value)
        assertEquals("key-c", rotator.activeKey(cfg, "session-3")?.value)
        assertEquals("key-a", rotator.activeKey(cfg, "session-4")?.value)
    }

    /** 轮询策略下同一会话仍粘住同一个 Key，不随游标漂移。 */
    @Test
    fun activeKey_roundRobin_sameSessionStaysSticky() {
        val rotator = ProviderKeyRotator()
        val cfg = multiKeyConfig(
            keys = listOf("key-a", "key-b"),
            strategy = KeyRotationStrategy.ROUND_ROBIN
        )

        val first = rotator.activeKey(cfg, "session-1")?.id
        repeat(3) {
            assertEquals(first, rotator.activeKey(cfg, "session-1")?.id)
        }
        // 其它会话不受影响，继续轮转
        assertEquals("key-b", rotator.activeKey(cfg, "session-2")?.value)
    }

    /** sessionId 为 null 时不建绑定：轮询策略每次调用都推进游标。 */
    @Test
    fun activeKey_nullSession_roundRobinAdvancesEveryCall() {
        val rotator = ProviderKeyRotator()
        val cfg = multiKeyConfig(
            keys = listOf("key-a", "key-b"),
            strategy = KeyRotationStrategy.ROUND_ROBIN
        )

        assertEquals("key-a", rotator.activeKey(cfg, null)?.value)
        assertEquals("key-b", rotator.activeKey(cfg, null)?.value)
        assertEquals("key-a", rotator.activeKey(cfg, null)?.value)
    }

    /** 冷却中的 Key 不被新会话选中。 */
    @Test
    fun activeKey_skipsCoolingDownKey() {
        val rotator = ProviderKeyRotator()
        val cfg = multiKeyConfig(keys = listOf("key-a", "key-b"), cooldownMinutes = 60)

        rotator.activeKey(cfg, "session-1")
        rotator.reportFailure("provider-1", "session-1", entryId("key-a"))

        // 新会话不能再选冷却中的 key-a
        assertEquals("key-b", rotator.activeKey(cfg, "session-2")?.value)
    }

    // ---------- currentKey ----------

    /** currentKey 返回最近一次 activeKey 选中的 Key。 */
    @Test
    fun currentKey_returnsLastSelectedKey() {
        val rotator = ProviderKeyRotator()
        val cfg = multiKeyConfig(keys = listOf("key-a", "key-b"))

        rotator.activeKey(cfg, "session-1") // 选中 key-a

        assertEquals("key-a", rotator.currentKey(cfg)?.value)
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
        assertEquals("key-a", rotator.activeKey(cfg, "session-1")?.value)
    }

    /** 冷启动（无任何历史调用）时，currentKey 返回第一个未冷却 Key。 */
    @Test
    fun currentKey_noSelection_returnsFirstUsableKey() {
        val rotator = ProviderKeyRotator()
        val cfg = multiKeyConfig(keys = listOf("key-a", "key-b"))

        // currentKey 不依赖 activeKey 建立的配置快照，直接可用
        assertEquals("key-a", rotator.currentKey(cfg)?.value)
    }

    // ---------- reportFailure ----------

    /** 失败即刻切换：不累计计数，首次上报即返回切换结果。 */
    @Test
    fun reportFailure_switchesImmediately() {
        val rotator = ProviderKeyRotator()
        val cfg = multiKeyConfig(keys = listOf("key-a", "key-b"))

        rotator.activeKey(cfg, "session-1") // 建立配置快照

        val switched = rotator.reportFailure("provider-1", "session-1", entryId("key-a"))

        assertNotNull(switched)
        assertEquals("key-b", switched!!.newEntry.value)
        assertEquals(2, switched.newIndex)
        assertEquals(2, switched.total)
    }

    /** 失败切换后重绑会话到新 Key：同会话后续调用都用新 Key。 */
    @Test
    fun reportFailure_rebindsSessionToNewKey() {
        val rotator = ProviderKeyRotator()
        val cfg = multiKeyConfig(keys = listOf("key-a", "key-b"))

        rotator.activeKey(cfg, "session-1") // key-a

        val switched = rotator.reportFailure("provider-1", "session-1", entryId("key-a"))

        assertNotNull(switched)
        assertEquals("key-b", switched!!.newEntry.value)
        assertEquals(2, switched.newIndex)
        assertEquals(2, switched.total)
        // 会话重绑到新 Key
        assertEquals("key-b", rotator.activeKey(cfg, "session-1")?.value)
    }

    /** 从未建立配置快照（没调用过 activeKey）时直接返回 null。 */
    @Test
    fun reportFailure_withoutPriorSetup_returnsNull() {
        val rotator = ProviderKeyRotator()
        val cfg = multiKeyConfig(keys = listOf("key-a", "key-b"))

        assertNull(rotator.reportFailure("provider-1", "session-1", entryId("key-a")))
    }

    /** 单 Key 配置无可切换目标：返回 null。 */
    @Test
    fun reportFailure_singleKey_returnsNull() {
        val rotator = ProviderKeyRotator()
        val cfg = config()

        rotator.activeKey(cfg, "session-1")

        assertNull(rotator.reportFailure("provider-1", "session-1", entryId("key-1")))
        assertNull(rotator.reportFailure("provider-1", "session-1", entryId("key-1")))
    }

    /** 上报不属于该 provider 的 Key：返回 null。 */
    @Test
    fun reportFailure_unknownKey_returnsNull() {
        val rotator = ProviderKeyRotator()
        val cfg = multiKeyConfig(keys = listOf("key-a", "key-b"))

        rotator.activeKey(cfg, "session-1")

        assertNull(rotator.reportFailure("provider-1", "session-1", entryId("key-unknown")))
    }

    /** 失败切换后，原 Key 进入冷却（冷却时长非零时）。 */
    @Test
    fun reportFailure_switchAppliesCooldown() {
        val rotator = ProviderKeyRotator()
        val cfg = multiKeyConfig(keys = listOf("key-a", "key-b"), cooldownMinutes = 60)

        rotator.activeKey(cfg, "session-1") // key-a
        rotator.reportFailure("provider-1", "session-1", entryId("key-a"))

        // key-a 冷却中，新会话不再选它
        assertEquals("key-b", rotator.activeKey(cfg, "session-2")?.value)
    }

    /** 冷却时长为 0 表示不冷却：切换后原 Key 仍可被新会话选中。 */
    @Test
    fun reportFailure_cooldownZero_noCooldownApplied() {
        val rotator = ProviderKeyRotator()
        val cfg = multiKeyConfig(keys = listOf("key-a", "key-b"), cooldownMinutes = 0)

        rotator.activeKey(cfg, "s1") // key-a
        rotator.reportFailure("provider-1", "s1", entryId("key-a"))

        // 无冷却，key-a 重新可用；顺序策略下新会话从第一个未冷却 Key 开始
        assertEquals("key-a", rotator.activeKey(cfg, "s2")?.value)
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
        val switched = rotator.reportFailure("provider-1", "s1", entryId("key-a"))

        // 游标=1：候选 [b,c,a]，避开 key-a → key-b
        assertEquals("key-b", switched!!.newEntry.value)
        // 游标被推进（1→2），下一个新会话从 key-c 起步
        assertEquals("key-c", rotator.activeKey(cfg, "s2")?.value)
    }

    /** 已试过的 Key 不再重复返回；候选全部试完时返回 null。 */
    @Test
    fun reportFailure_excludesTriedKeysAndReturnsNullWhenExhausted() {
        val rotator = ProviderKeyRotator()
        val cfg = multiKeyConfig(keys = listOf("key-a", "key-b", "key-c"))

        rotator.activeKey(cfg, "session-1")
        val switched = rotator.reportFailure(
            "provider-1", "session-1", entryId("key-c"),
            setOf(entryId("key-a"), entryId("key-b"), entryId("key-c"))
        )

        assertNull(switched)
    }

    /** 没有可切换的候选时不冷却当前 Key，避免把最后一个可用 Key 锁死。 */
    @Test
    fun reportFailure_noAlternative_doesNotCoolDownCurrentKey() {
        val rotator = ProviderKeyRotator()
        val cfg = multiKeyConfig(keys = listOf("key-a", "key-b"), cooldownMinutes = 60)

        rotator.activeKey(cfg, "s1") // key-a
        rotator.reportFailure("provider-1", "s1", entryId("key-a")) // 切到 key-b，key-a 冷却

        // key-b 是最后一个可用候选：返回 null 且不冷却它
        assertNull(rotator.reportFailure("provider-1", "s1", entryId("key-b")))
        assertEquals("key-b", rotator.activeKey(cfg, "s2")?.value)
    }

    // ---------- 同值不同覆盖地址 ----------

    /** 同一条 Key 配不同中转是两个通道：失败后能切到同值的另一个中转。 */
    @Test
    fun reportFailure_sameValueDifferentBaseUrl_switchesRelay() {
        val rotator = ProviderKeyRotator()
        val relayA = ProviderKey("sk-a", baseUrl = "https://relay-a.example.com")
        val relayB = ProviderKey("sk-a", baseUrl = "https://relay-b.example.com")
        val cfg = config(
            apiKey = "unused",
            apiKeys = listOf(relayA, relayB),
            multiKeyEnabled = true
        )

        assertEquals(relayA.baseUrl, rotator.activeKey(cfg, "s1")?.baseUrl)

        val switched = rotator.reportFailure("provider-1", "s1", relayA.id, setOf(relayA.id))

        assertEquals(relayB, switched?.newEntry)
    }

    /** 失败只冷却出错的那个中转，同值的另一个中转仍可被新会话选中。 */
    @Test
    fun reportFailure_sameValueDifferentBaseUrl_coolsOnlyFailedRelay() {
        val rotator = ProviderKeyRotator()
        val relayA = ProviderKey("sk-a", baseUrl = "https://relay-a.example.com")
        val relayB = ProviderKey("sk-a", baseUrl = "https://relay-b.example.com")
        val cfg = config(
            apiKey = "unused",
            apiKeys = listOf(relayA, relayB),
            multiKeyEnabled = true,
            keyCooldownMinutes = 60
        )

        rotator.activeKey(cfg, "s1")
        rotator.reportFailure("provider-1", "s1", relayA.id)

        assertEquals(relayB.baseUrl, rotator.activeKey(cfg, "s2")?.baseUrl)
    }

    // ---------- 加权轮询 ----------

    /** 加权轮询：新会话按权重比例分配（1:3 时高权重 Key 约为低权重的三倍）。 */
    @Test
    fun activeKey_weightedRoundRobin_distributesByWeight() {
        val rotator = ProviderKeyRotator()
        val cfg = config(
            apiKeys = listOf(ProviderKey("key-a", 1), ProviderKey("key-b", 3)),
            multiKeyEnabled = true,
            keyRotationStrategy = KeyRotationStrategy.ROUND_ROBIN
        )

        val counts = mutableMapOf<String, Int>()
        repeat(40) { i ->
            val key = rotator.activeKey(cfg, "session-$i")!!.value
            counts[key] = (counts[key] ?: 0) + 1
        }

        assertEquals(10, counts["key-a"])
        assertEquals(30, counts["key-b"])
    }

    /** 权重 ≤ 0 的 Key 不参与调度：轮询下始终不会被选中。 */
    @Test
    fun activeKey_nonPositiveWeightIsExcluded() {
        val rotator = ProviderKeyRotator()
        val cfg = config(
            apiKeys = listOf(ProviderKey("key-a", 0), ProviderKey("key-b", 1)),
            multiKeyEnabled = true,
            keyRotationStrategy = KeyRotationStrategy.ROUND_ROBIN
        )

        repeat(5) { i ->
            assertEquals("key-b", rotator.activeKey(cfg, "session-$i")?.value)
        }
    }

    /** 所有 Key 权重都 ≤ 0 时没有可调度的 Key，返回 null。 */
    @Test
    fun activeKey_allNonPositiveWeights_returnsNull() {
        val rotator = ProviderKeyRotator()
        val cfg = config(
            apiKeys = listOf(ProviderKey("key-a", 0), ProviderKey("key-b", 0)),
            multiKeyEnabled = true
        )

        assertNull(rotator.activeKey(cfg, "session-1"))
    }
}
