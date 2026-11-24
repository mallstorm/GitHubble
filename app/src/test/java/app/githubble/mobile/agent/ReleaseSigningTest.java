package app.githubble.mobile.agent;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import org.junit.Test;

public class ReleaseSigningTest {
    @Test public void generatedKeystoreLoadsWithPasswordAndCertificateIsSelfSigned() throws Exception {
        ReleaseSigning.Bundle b = ReleaseSigning.generate("release", "Telegram-Premium", "GitHubble");
        KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(new ByteArrayInputStream(b.keystore), b.password.toCharArray());
        assertTrue(store.isKeyEntry("release"));
        assertNotNull(store.getKey("release", b.password.toCharArray()));
        X509Certificate cert = (X509Certificate) store.getCertificate("release");
        cert.verify(cert.getPublicKey());
        cert.checkValidity();
        assertTrue(cert.getSubjectX500Principal().getName().contains("Telegram-Premium"));
        assertTrue(cert.getNotAfter().getTime() - System.currentTimeMillis() > 20L * 365 * 86400000L);
        assertEquals(95, b.sha256.length());
        assertTrue(b.password.length() >= 24);
    }
    @Test(expected = IllegalArgumentException.class) public void rejectsUnsafeAlias() throws Exception {
        ReleaseSigning.generate("a b;rm", "x", "y");
    }
}
