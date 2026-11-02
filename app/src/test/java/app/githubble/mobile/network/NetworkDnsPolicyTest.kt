package app.githubble.mobile.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkDnsPolicyTest {
    @Test fun systemDnsClearsEndpoint() {
        assertEquals(DnsSettings("system", ""), NetworkDnsPolicy.normalize("system", "https://dns.example/dns-query"))
    }

    @Test fun dohBareHostExpandsToHttpsDnsQuery() {
        val value = NetworkDnsPolicy.normalize("doh", "dns.example.org")
        assertEquals("doh", value.mode)
        assertEquals("https://dns.example.org/dns-query", value.endpoint)
    }

    @Test fun dohFullEndpointIsPreserved() {
        val value = NetworkDnsPolicy.normalize("doh", "https://dns.example.org/custom")
        assertEquals("https://dns.example.org/custom", value.endpoint)
    }

    @Test fun dotDefaultsToTlsSchemeAndPortSemantics() {
        val value = NetworkDnsPolicy.normalize("dot", "dns.example.org")
        assertEquals("dot", value.mode)
        assertEquals("tls://dns.example.org", value.endpoint)
    }

    @Test fun rejectsWrongSchemesAndDoq() {
        assertTrue(runCatching { NetworkDnsPolicy.normalize("doh", "http://dns.example.org/dns-query") }.isFailure)
        assertTrue(runCatching { NetworkDnsPolicy.normalize("dot", "https://dns.example.org") }.isFailure)
        assertTrue(runCatching { NetworkDnsPolicy.normalize("doq", "quic://dns.example.org") }.isFailure)
    }
}

