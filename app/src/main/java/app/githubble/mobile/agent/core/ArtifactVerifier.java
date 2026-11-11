package app.githubble.mobile.agent.core;

import java.io.File;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Independent APK checks (TZ 9.4): a green workflow is not a delivered app. Every check ends
 * PASS, FAIL or UNVERIFIED; only a report with no FAIL and no UNVERIFIED is acceptable.
 */
public final class ArtifactVerifier {
    public enum Status { PASS, FAIL, UNVERIFIED }

    public static final class Check {
        public final String name, detail;
        public final Status status;
        Check(String n, Status s, String d) { name = n; status = s; detail = d; }
    }

    public static final class Expectation {
        public String fileNameRegex = "";      // empty: not checked
        public String packageId = "";          // empty: not checked
        public long versionCode = -1;          // -1: not checked
        public String versionName = "";        // empty: not checked
        public String variant = "";            // "debug" | "release" | "" (not checked)
        public boolean requireSignature;
        public String sha256 = "";             // lower-case hex; empty: not checked
        public long minBytes = 1024;
    }

    public static final class Report {
        public final List<Check> checks = new ArrayList<>();
        public String sha256 = "";
        public ApkManifestReader.Identity identity;
        public boolean ok() {
            for (Check c : checks) if (c.status != Status.PASS) return false;
            return !checks.isEmpty();
        }
        public String summary() {
            StringBuilder sb = new StringBuilder();
            for (Check c : checks) sb.append(c.status).append(' ').append(c.name).append(": ").append(c.detail).append('\n');
            return sb.toString();
        }
        void add(String n, Status s, String d) { checks.add(new Check(n, s, d)); }
    }

    private ArtifactVerifier() {}

    public static Report verify(File apk, Expectation e) {
        Report r = new Report();
        if (apk == null || !apk.isFile()) { r.add("exists", Status.FAIL, "файл отсутствует"); return r; }
        r.add("exists", Status.PASS, apk.getName());
        if (!e.fileNameRegex.isEmpty()) {
            boolean ok = apk.getName().matches(e.fileNameRegex);
            r.add("file-name", ok ? Status.PASS : Status.FAIL, apk.getName());
        }
        long size = apk.length();
        r.add("non-empty", size >= e.minBytes ? Status.PASS : Status.FAIL, size + " байт");
        if (size < e.minBytes) return r;
        try {
            r.sha256 = sha256(apk);
            if (!e.sha256.isEmpty())
                r.add("sha256", r.sha256.equalsIgnoreCase(e.sha256) ? Status.PASS : Status.FAIL, r.sha256);
        } catch (Exception ex) {
            r.add("sha256", Status.FAIL, ex.getClass().getSimpleName());
            return r;
        }
        byte[] manifest = null;
        boolean dex = false, v1 = false;
        try (ZipFile zip = new ZipFile(apk)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            byte[] buf = new byte[64 * 1024];
            int count = 0;
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                count++;
                String n = entry.getName();
                if (n.contains("..") || n.startsWith("/")) { r.add("zip-paths", Status.FAIL, "небезопасный путь " + n); return r; }
                if (n.equals("classes.dex")) dex = true;
                if (n.startsWith("META-INF/") && (n.endsWith(".RSA") || n.endsWith(".DSA") || n.endsWith(".EC"))) v1 = true;
                // Reading to the end makes the JDK verify each entry's CRC32 (integrity).
                try (InputStream in = zip.getInputStream(entry)) {
                    if (n.equals("AndroidManifest.xml")) manifest = readAll(in, 4 * 1024 * 1024);
                    else while (in.read(buf) != -1) { /* drain */ }
                }
            }
            r.add("zip-integrity", Status.PASS, count + " записей, CRC проверены");
        } catch (Exception ex) {
            r.add("zip-integrity", Status.FAIL, ex.getClass().getSimpleName() + ": " + ex.getMessage());
            return r;
        }
        r.add("classes.dex", dex ? Status.PASS : Status.FAIL, dex ? "есть" : "нет");
        if (manifest == null) { r.add("manifest", Status.FAIL, "нет AndroidManifest.xml"); return r; }
        try {
            ApkManifestReader.Identity id = ApkManifestReader.read(manifest);
            r.identity = id;
            r.add("manifest", Status.PASS, id.packageName + " " + id.versionName + " (" + id.versionCode + ")");
            if (!e.packageId.isEmpty())
                r.add("package-id", id.packageName.equals(e.packageId) ? Status.PASS : Status.FAIL, id.packageName);
            if (e.versionCode >= 0)
                r.add("version-code", id.versionCode == e.versionCode ? Status.PASS : Status.FAIL, String.valueOf(id.versionCode));
            if (!e.versionName.isEmpty())
                r.add("version-name", id.versionName.equals(e.versionName) ? Status.PASS : Status.FAIL, id.versionName);
            if (e.variant.equals("release"))
                r.add("variant", id.debuggable ? Status.FAIL : Status.PASS, id.debuggable ? "debuggable=true" : "не debuggable");
            else if (e.variant.equals("debug"))
                r.add("variant", id.debuggable ? Status.PASS : Status.FAIL, id.debuggable ? "debuggable=true" : "не debuggable");
        } catch (ApkManifestReader.ParseException ex) {
            // Cannot prove identity: never a pass.
            r.add("manifest", Status.UNVERIFIED, ex.getMessage());
        }
        if (e.requireSignature) {
            boolean v2 = hasSigningBlock(apk);
            r.add("signature", (v1 || v2) ? Status.PASS : Status.FAIL,
                v2 ? "APK Signing Block (v2+)" : v1 ? "JAR-подпись (v1)" : "подпись не найдена");
            // Presence is checked here; cryptographic validity needs apksigner (done in CI).
        }
        return r;
    }

    static boolean hasSigningBlock(File apk) {
        try (java.io.RandomAccessFile f = new java.io.RandomAccessFile(apk, "r")) {
            // Scan the tail for the magic that precedes the central directory.
            long len = f.length();
            long window = Math.min(len, 8L * 1024 * 1024);
            byte[] tail = new byte[(int) window];
            f.seek(len - window);
            f.readFully(tail);
            byte[] magic = "APK Sig Block 42".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            outer:
            for (int i = tail.length - magic.length; i >= 0; i--) {
                for (int j = 0; j < magic.length; j++) if (tail[i + j] != magic[j]) continue outer;
                return true;
            }
        } catch (Exception ignored) { /* treated as absent */ }
        return false;
    }

    private static byte[] readAll(InputStream in, int max) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            if (out.size() + n > max) throw new IllegalStateException("слишком большой манифест");
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    static String sha256(File f) throws Exception {
        MessageDigest d = MessageDigest.getInstance("SHA-256");
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) d.update(buf, 0, n);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : d.digest()) sb.append(String.format("%02x", b & 255));
        return sb.toString();
    }
}
