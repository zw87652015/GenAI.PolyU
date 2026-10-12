package hk.edu.polyu.genai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PortalClientTest {
    @Test
    fun httpsStaysInTheWebView() {
        assertTrue(UrlRouting.isWebScheme("https"))
        assertTrue(UrlRouting.isWebScheme("HTTP"))
    }

    @Test
    fun otherSchemesLeaveTheWebView() {
        assertFalse(UrlRouting.isWebScheme("intent"))
        assertFalse(UrlRouting.isWebScheme("mailto"))
        assertFalse(UrlRouting.isWebScheme(null))
    }

    @Test
    fun signInFixRewritesTheDesktopViewport() {
        assertTrue(PortalScripts.SOURCE.contains("width=device-width, initial-scale=1"))
        assertTrue(PortalScripts.SOURCE.contains("adfs.polyu.edu.hk"))
        assertTrue(PortalScripts.SOURCE.contains("#brandingWrapper"))
        assertTrue(PortalScripts.SOURCE.contains("polyu-genai-mobile"))
    }
}
