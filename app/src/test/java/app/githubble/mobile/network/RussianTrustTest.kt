package app.githubble.mobile.network

import org.junit.Assert.*
import org.junit.Test

class RussianTrustTest {
    @Test fun bundledRootsParseAndChainToTheRoot() {
        val (root, sub) = RussianTrust.anchors()
        assertTrue(root.subjectX500Principal.name.contains("Russian Trusted Root CA"))
        assertEquals(root.subjectX500Principal, root.issuerX500Principal)
        root.verify(root.publicKey)
        assertTrue(sub.subjectX500Principal.name.contains("Russian Trusted Sub CA"))
        sub.verify(root.publicKey)
    }

    @Test fun rootFingerprintMatchesThePublishedOne() {
        val root = RussianTrust.anchors().first()
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(root.encoded).joinToString("") { "%02X".format(it) }
        assertEquals("D26D2D0231B7C39F92CC738512BA541035" + "19E4405D68B5BD703E9788CA8ECF31", hash)
    }

    @Test fun onlyGigaChatHostsAreAffected() {
        assertTrue(RussianTrust.isGigaChatHost("api.giga.chat"))
        assertTrue(RussianTrust.isGigaChatHost("ngw.devices.sberbank.ru"))
        assertFalse(RussianTrust.isGigaChatHost("api.openai.com"))
        assertFalse(RussianTrust.isGigaChatHost("evil.giga.chat.example.com"))
    }
}
