package com.aicode.feature.settings.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SponsorCatalogTest {

    @Test
    fun parseSponsorCatalog_parsesMethodsAndUsers() {
        val raw = """
            {
              "methods": [
                {
                  "id": "afdian",
                  "name": { "zh": "爱发电", "en": "Afdian" },
                  "url": "https://ifdian.net/a/jieapi",
                  "icon": "heart",
                  "unknownField": true
                }
              ],
              "users": [
                {
                  "name": "arsnyeon",
                  "avatar": "https://example.com/a.png",
                  "url": "https://example.com/u/arsnyeon"
                },
                {
                  "name": "   "
                }
              ]
            }
        """.trimIndent()

        val parsed = SponsorCatalog.parseSponsorCatalog(raw)
        assertNotNull(parsed)
        assertEquals(1, parsed!!.methods.size)
        assertEquals("afdian", parsed.methods[0].id)
        assertEquals("https://ifdian.net/a/jieapi", parsed.methods[0].url)
        assertEquals(1, parsed.users.size)
        assertEquals("arsnyeon", parsed.users[0].name)
        assertEquals("https://example.com/u/arsnyeon", parsed.users[0].url)
    }

    @Test
    fun parseSponsorCatalog_invalidOrEmptyReturnsNull() {
        assertNull(SponsorCatalog.parseSponsorCatalog(null))
        assertNull(SponsorCatalog.parseSponsorCatalog("   "))
        assertNull(SponsorCatalog.parseSponsorCatalog("{not-json"))
        assertNull(SponsorCatalog.parseSponsorCatalog("""{"methods":[],"users":[]}"""))
    }

    @Test
    fun parseSponsorCatalog_fallsBackToDefaultMethodWhenOnlyUsersProvided() {
        val parsed = SponsorCatalog.parseSponsorCatalog("""{"users":[{"name":"alice"}]}""")
        assertNotNull(parsed)
        assertEquals(SponsorCatalog.DEFAULT_METHODS, parsed!!.methods)
        assertEquals(1, parsed.users.size)
        assertEquals("alice", parsed.users[0].name)
    }

    @Test
    fun repoSponsorsJson_isValidAndContainsInitialEntries() {
        val candidates = listOf(
            File("data/sponsors.json"),
            File("../data/sponsors.json")
        )
        val file = candidates.firstOrNull { it.isFile }
        assertNotNull("Expected data/sponsors.json to exist", file)
        val parsed = SponsorCatalog.parseSponsorCatalog(file!!.readText())
        assertNotNull(parsed)
        assertTrue(parsed!!.methods.any { it.url == SponsorCatalog.DEFAULT_AFDIAN_URL })
        assertTrue(parsed.users.any { it.name == "arsnyeon" })
    }
}
