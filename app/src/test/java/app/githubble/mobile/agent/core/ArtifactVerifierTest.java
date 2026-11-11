package app.githubble.mobile.agent.core;

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.Test;

public class ArtifactVerifierTest {
    /** Minimal binary AndroidManifest (UTF-16 string pool, <manifest> + <application>). */
    static byte[] manifest(String pkg, int versionCode, String versionName, boolean debuggable) {
        String[] pool = {"versionCode", "versionName", "package", "debuggable", "manifest", "application", pkg, versionName};
        ByteArrayOutputStream strings = new ByteArrayOutputStream();
        int[] offsets = new int[pool.length];
        for (int i = 0; i < pool.length; i++) {
            offsets[i] = strings.size();
            ByteBuffer s = ByteBuffer.allocate(2 + pool[i].length() * 2 + 2).order(ByteOrder.LITTLE_ENDIAN);
            s.putShort((short) pool[i].length());
            for (char c : pool[i].toCharArray()) s.putChar(c);
            s.putShort((short) 0);
            strings.write(s.array(), 0, s.array().length);
        }
        while (strings.size() % 4 != 0) strings.write(0);
        int poolHeader = 28, poolSize = poolHeader + pool.length * 4 + strings.size();
        ByteBuffer p = ByteBuffer.allocate(poolSize).order(ByteOrder.LITTLE_ENDIAN);
        p.putShort((short) 1).putShort((short) poolHeader).putInt(poolSize).putInt(pool.length).putInt(0)
            .putInt(0).putInt(poolHeader + pool.length * 4).putInt(0);
        for (int o : offsets) p.putInt(o);
        p.put(strings.toByteArray());

        ByteBuffer m = element(4, new int[][] {
            {2, 6, 0x03, 6}, {1, 7, 0x03, 7}, {0, -1, 0x10, versionCode}});
        // attribute rows are {nameIdx, rawValueIdx, dataType, data}
        ByteBuffer a = element(5, new int[][] {{3, -1, 0x12, debuggable ? -1 : 0}});
        int total = 8 + poolSize + m.capacity() + a.capacity();
        ByteBuffer out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
        out.putShort((short) 3).putShort((short) 8).putInt(total);
        out.put(p.array()).put(m.array()).put(a.array());
        return out.array();
    }

    private static ByteBuffer element(int nameIdx, int[][] attrs) {
        int size = 16 + 20 + attrs.length * 20;
        ByteBuffer b = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        b.putShort((short) 0x0102).putShort((short) 16).putInt(size).putInt(1).putInt(-1);
        b.putInt(-1).putInt(nameIdx).putShort((short) 20).putShort((short) 20).putShort((short) attrs.length)
            .putShort((short) 0).putShort((short) 0).putShort((short) 0);
        for (int[] at : attrs) {
            b.putInt(-1).putInt(at[0]).putInt(at[1]);
            b.putShort((short) 8).put((byte) 0).put((byte) at[2]).putInt(at[3]);
        }
        return b;
    }

    static File apk(byte[] manifest, boolean withDex, boolean signed) throws Exception {
        File f = File.createTempFile("test", ".apk");
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(f))) {
            if (manifest != null) { z.putNextEntry(new ZipEntry("AndroidManifest.xml")); z.write(manifest); z.closeEntry(); }
            if (withDex) { z.putNextEntry(new ZipEntry("classes.dex")); z.write(new byte[4096]); z.closeEntry(); }
            if (signed) { z.putNextEntry(new ZipEntry("META-INF/CERT.RSA")); z.write(new byte[64]); z.closeEntry(); }
        }
        return f;
    }

    private static ArtifactVerifier.Expectation expect() {
        ArtifactVerifier.Expectation e = new ArtifactVerifier.Expectation();
        e.packageId = "app.demo"; e.versionCode = 7; e.versionName = "1.2.3"; e.minBytes = 100;
        return e;
    }

    @Test public void validReleaseApkPasses() throws Exception {
        ArtifactVerifier.Expectation e = expect(); e.variant = "release"; e.requireSignature = true;
        ArtifactVerifier.Report r = ArtifactVerifier.verify(apk(manifest("app.demo", 7, "1.2.3", false), true, true), e);
        assertTrue(r.summary(), r.ok());
        assertEquals("app.demo", r.identity.packageName);
        assertEquals(7, r.identity.versionCode);
        assertEquals(64, r.sha256.length());
    }

    @Test public void wrongIdentityFails() throws Exception {
        File f = apk(manifest("app.other", 7, "1.2.3", false), true, false);
        assertFalse(ArtifactVerifier.verify(f, expect()).ok());
        ArtifactVerifier.Expectation e = expect(); e.versionCode = 8;
        assertFalse(ArtifactVerifier.verify(apk(manifest("app.demo", 7, "1.2.3", false), true, false), e).ok());
        e = expect(); e.versionName = "9";
        assertFalse(ArtifactVerifier.verify(apk(manifest("app.demo", 7, "1.2.3", false), true, false), e).ok());
    }

    @Test public void debuggableBuildIsNotAReleaseVariant() throws Exception {
        ArtifactVerifier.Expectation e = expect(); e.variant = "release";
        assertFalse(ArtifactVerifier.verify(apk(manifest("app.demo", 7, "1.2.3", true), true, false), e).ok());
        e.variant = "debug";
        assertTrue(ArtifactVerifier.verify(apk(manifest("app.demo", 7, "1.2.3", true), true, false), e).ok());
    }

    @Test public void missingSignatureFailsWhenRequired() throws Exception {
        ArtifactVerifier.Expectation e = expect(); e.requireSignature = true;
        assertFalse(ArtifactVerifier.verify(apk(manifest("app.demo", 7, "1.2.3", false), true, false), e).ok());
    }

    @Test public void missingFileEmptyFileAndNonZipFail() throws Exception {
        assertFalse(ArtifactVerifier.verify(new File("/nonexistent.apk"), expect()).ok());
        File empty = File.createTempFile("empty", ".apk");
        assertFalse(ArtifactVerifier.verify(empty, expect()).ok());
        File junk = File.createTempFile("junk", ".apk");
        Files.write(junk.toPath(), new byte[5000]);
        assertFalse(ArtifactVerifier.verify(junk, expect()).ok());
    }

    @Test public void missingDexOrManifestFails() throws Exception {
        assertFalse(ArtifactVerifier.verify(apk(manifest("app.demo", 7, "1.2.3", false), false, false), expect()).ok());
        assertFalse(ArtifactVerifier.verify(apk(null, true, false), expect()).ok());
    }

    @Test public void unreadableManifestIsUnverifiedNeverPass() throws Exception {
        ArtifactVerifier.Report r = ArtifactVerifier.verify(apk(new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9}, true, false), expect());
        assertFalse(r.ok());
        boolean unverified = false;
        for (ArtifactVerifier.Check c : r.checks) if (c.status == ArtifactVerifier.Status.UNVERIFIED) unverified = true;
        assertTrue(unverified);
    }

    @Test public void shaMismatchFails() throws Exception {
        ArtifactVerifier.Expectation e = expect(); e.sha256 = "00";
        assertFalse(ArtifactVerifier.verify(apk(manifest("app.demo", 7, "1.2.3", false), true, false), e).ok());
    }

    @Test public void fileNameRuleIsEnforced() throws Exception {
        ArtifactVerifier.Expectation e = expect(); e.fileNameRegex = "GitHubble-.*\\.apk";
        assertFalse(ArtifactVerifier.verify(apk(manifest("app.demo", 7, "1.2.3", false), true, false), e).ok());
    }
}
