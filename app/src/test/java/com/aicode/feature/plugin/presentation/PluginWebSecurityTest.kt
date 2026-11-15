package com.aicode.feature.plugin.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginWebSecurityTest {

    @Test
    fun htmlResponseCarriesContentSecurityPolicy() {
        assertEquals(HTML_CONTENT_SECURITY_POLICY, contentSecurityPolicyForMime("text/html"))
    }

    @Test
    fun nonHtmlResponsesDoNotCarryContentSecurityPolicy() {
        assertNull(contentSecurityPolicyForMime("text/css"))
        assertNull(contentSecurityPolicyForMime("application/javascript"))
        assertNull(contentSecurityPolicyForMime("application/json"))
        assertNull(contentSecurityPolicyForMime("image/png"))
        assertNull(contentSecurityPolicyForMime("application/octet-stream"))
    }

    @Test
    fun contentSecurityPolicyBlocksRemoteScriptsAndFrames() {
        assertTrue(
            HTML_CONTENT_SECURITY_POLICY.contains(
                "script-src 'self' appassets.androidplatform.net 'unsafe-inline' 'unsafe-eval'"
            )
        )
        assertFalse(HTML_CONTENT_SECURITY_POLICY.contains("script-src 'self' appassets.androidplatform.net https:"))
        assertTrue(HTML_CONTENT_SECURITY_POLICY.contains("frame-src 'none'"))
        assertTrue(HTML_CONTENT_SECURITY_POLICY.contains("object-src 'none'"))
        assertTrue(HTML_CONTENT_SECURITY_POLICY.contains("base-uri 'none'"))
        assertTrue(HTML_CONTENT_SECURITY_POLICY.contains("frame-ancestors 'none'"))
    }

    @Test
    fun contentSecurityPolicyKeepsRequiredSources() {
        assertTrue(
            HTML_CONTENT_SECURITY_POLICY.contains(
                "default-src 'self' appassets.androidplatform.net data: blob:"
            )
        )
        assertTrue(
            HTML_CONTENT_SECURITY_POLICY.contains(
                "style-src 'self' appassets.androidplatform.net 'unsafe-inline' https: data:"
            )
        )
        assertTrue(
            HTML_CONTENT_SECURITY_POLICY.contains(
                "img-src 'self' appassets.androidplatform.net data: blob: https:"
            )
        )
        assertTrue(
            HTML_CONTENT_SECURITY_POLICY.contains(
                "font-src 'self' appassets.androidplatform.net data: https:"
            )
        )
        assertTrue(
            HTML_CONTENT_SECURITY_POLICY.contains(
                "media-src 'self' appassets.androidplatform.net data: blob: https:"
            )
        )
        assertTrue(
            HTML_CONTENT_SECURITY_POLICY.contains(
                "connect-src 'self' appassets.androidplatform.net http: https:"
            )
        )
    }

    @Test
    fun mainFrameFromVirtualOriginIsTrusted() {
        assertTrue(isTrustedBridgeFrame(true, "https", "appassets.androidplatform.net", -1))
        assertTrue(isTrustedBridgeFrame(true, "HTTPS", "AppAssets.AndroidPlatform.Net", 443))
    }

    @Test
    fun subFramesAreRejected() {
        assertFalse(isTrustedBridgeFrame(false, "https", "appassets.androidplatform.net", -1))
        assertFalse(isTrustedBridgeFrame(false, "https", "appassets.androidplatform.net", 443))
    }

    @Test
    fun foreignOriginsAreRejected() {
        assertFalse(isTrustedBridgeFrame(true, "http", "appassets.androidplatform.net", -1))
        assertFalse(isTrustedBridgeFrame(true, "https", "evil.example", -1))
        assertFalse(isTrustedBridgeFrame(true, "https", "appassets.androidplatform.net.evil.example", -1))
        assertFalse(isTrustedBridgeFrame(true, "https", "appassets.androidplatform.net", 8443))
        assertFalse(isTrustedBridgeFrame(true, "https", "appassets.androidplatform.net", 80))
    }

    @Test
    fun missingOriginIsRejected() {
        assertFalse(isTrustedBridgeFrame(true, null, null, -1))
        assertFalse(isTrustedBridgeFrame(true, "about", "", -1))
    }
}
