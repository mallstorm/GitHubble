package app.githubble.mobile.agent;

import app.githubble.mobile.mirror.MirrorSecrets;
import app.githubble.mobile.network.GitHubApiClient;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.Base64;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import org.json.JSONObject;

/**
 * Generates an Android release keystore on the device and seals it into the repository's Actions secrets.
 * The model never sees a secret value: it only receives secret names and the certificate fingerprint.
 */
public final class ReleaseSigning {
    public static final String S_KEYSTORE = "ANDROID_KEYSTORE_BASE64", S_STORE_PASSWORD = "ANDROID_KEYSTORE_PASSWORD",
        S_ALIAS = "ANDROID_KEY_ALIAS", S_KEY_PASSWORD = "ANDROID_KEY_PASSWORD";
    private static final String API = "https://api.github.com/repos/";
    private static final long VALID_DAYS = 365L * 30;

    public static final class Bundle {
        public final byte[] keystore; public final String password, alias, sha256;
        Bundle(byte[] keystore, String password, String alias, String sha256) {
            this.keystore = keystore; this.password = password; this.alias = alias; this.sha256 = sha256;
        }
    }

    private ReleaseSigning() {}

    public static Bundle generate(String alias, String commonName, String organization) throws Exception {
        if (alias == null || !alias.matches("[A-Za-z0-9_-]{1,40}")) throw new IllegalArgumentException("Некорректный alias ключа");
        SecureRandom random = new SecureRandom();
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048, random);
        KeyPair pair = generator.generateKeyPair();
        X509Certificate cert = selfSigned(pair, commonName, organization, random);
        String password = password(random);
        KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(null, null);
        store.setKeyEntry(alias, pair.getPrivate(), password.toCharArray(), new Certificate[]{cert});
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        store.store(out, password.toCharArray());
        String fingerprint = hex(MessageDigest.getInstance("SHA-256").digest(cert.getEncoded()));
        return new Bundle(out.toByteArray(), password, alias, fingerprint);
    }

    static String password(SecureRandom random) {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 28; i++) sb.append(chars.charAt(random.nextInt(chars.length())));
        return sb.toString();
    }

    /** Names of the four Actions secrets that already exist in the repository. */
    public static boolean exists(GitHubApiClient api, String repo) throws Exception {
        try { api.getJsonFresh(API + repo + "/actions/secrets/" + S_KEYSTORE); return true; }
        catch (GitHubApiClient.ApiException e) { if (e.statusCode == 404) return false; throw e; }
    }

    public static void publish(GitHubApiClient api, String repo, Bundle bundle) throws Exception {
        publishValues(api, repo, bundle.keystore, bundle.password, bundle.alias, bundle.password);
    }

    /** Writes the four signing secrets from any keystore, e.g. one the user already has (JKS or PKCS12). */
    public static void publishValues(GitHubApiClient api, String repo, byte[] keystore, String storePassword, String alias, String keyPassword) throws Exception {
        JSONObject key = MirrorSecrets.publicKey(api, repo);
        put(api, repo, key, S_KEYSTORE, Base64.getEncoder().encodeToString(keystore));
        put(api, repo, key, S_STORE_PASSWORD, storePassword);
        put(api, repo, key, S_ALIAS, alias);
        put(api, repo, key, S_KEY_PASSWORD, keyPassword);
    }

    /** Opens a user's keystore with its password to make sure the file and the alias are right before anything is uploaded. */
    public static String verifyKeystore(byte[] keystore, String storePassword, String alias, String keyPassword) throws Exception {
        java.security.KeyStore store = null;
        Exception failure = null;
        for (String type : new String[]{"PKCS12", "JKS"}) {
            try {
                java.security.KeyStore candidate = java.security.KeyStore.getInstance(type);
                candidate.load(new java.io.ByteArrayInputStream(keystore), storePassword.toCharArray());
                store = candidate; break;
            } catch (Exception e) { failure = e; }
        }
        if (store == null) throw new IllegalArgumentException("Не удалось открыть keystore: проверьте файл и пароль хранилища" + (failure == null ? "" : " (" + failure.getClass().getSimpleName() + ")"));
        if (!store.isKeyEntry(alias)) throw new IllegalArgumentException("В keystore нет ключа с alias «" + alias + "»");
        try { store.getKey(alias, keyPassword.toCharArray()); }
        catch (java.security.UnrecoverableKeyException e) { throw new IllegalArgumentException("Неверный пароль ключа"); }
        java.security.cert.Certificate cert = store.getCertificate(alias);
        return cert == null ? "" : hex(MessageDigest.getInstance("SHA-256").digest(cert.getEncoded()));
    }

    /** Keeps a local copy: losing the key would make every already-signed app impossible to update. */
    public static void backup(android.content.Context context, String repo, Bundle bundle) throws Exception {
        java.io.File dir = new java.io.File(context.getFilesDir(), "release-signing");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new java.io.IOException("Не удалось создать хранилище ключей");
        JSONObject json = new JSONObject().put("repository", repo).put("alias", bundle.alias).put("password", bundle.password)
            .put("sha256", bundle.sha256).put("created", System.currentTimeMillis())
            .put("keystore_base64", Base64.getEncoder().encodeToString(bundle.keystore));
        java.io.File file = new java.io.File(dir, repo.replace('/', '_') + ".json");
        java.nio.file.Files.write(file.toPath(), json.toString().getBytes(StandardCharsets.UTF_8));
        file.setReadable(false, false); file.setReadable(true, true); file.setWritable(false, false); file.setWritable(true, true);
    }

    private static void put(GitHubApiClient api, String repo, JSONObject key, String name, String value) throws Exception {
        byte[] raw = value.getBytes(StandardCharsets.UTF_8);
        try {
            api.putJson(API + repo + "/actions/secrets/" + name, new JSONObject()
                .put("key_id", key.getString("key_id"))
                .put("encrypted_value", MirrorSecrets.encryptForGitHub(raw, key.getString("key"))));
        } finally { java.util.Arrays.fill(raw, (byte) 0); }
    }

    // ---- minimal DER writer: a self-signed X.509 v3 certificate without third-party crypto libraries -------------

    static X509Certificate selfSigned(KeyPair pair, String cn, String org, SecureRandom random) throws Exception {
        byte[] sigAlg = seq(oid("1.2.840.113549.1.1.11"), new byte[]{0x05, 0x00});
        byte[] name = name(cn, org);
        long now = System.currentTimeMillis() - 60_000L;
        byte[] validity = seq(time(new Date(now)), time(new Date(now + VALID_DAYS * 86_400_000L)));
        BigInteger serial = new BigInteger(120, random).add(BigInteger.ONE);
        byte[] tbs = seq(tlv(0xA0, tlv(0x02, new byte[]{2})), tlv(0x02, serial.toByteArray()), sigAlg, name, validity, name,
            pair.getPublic().getEncoded());
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(pair.getPrivate());
        signature.update(tbs);
        byte[] bits = signature.sign();
        byte[] bitString = new byte[bits.length + 1];
        System.arraycopy(bits, 0, bitString, 1, bits.length);
        byte[] der = seq(tbs, sigAlg, tlv(0x03, bitString));
        return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(new java.io.ByteArrayInputStream(der));
    }

    private static byte[] name(String cn, String org) {
        return seq(rdn("2.5.4.10", org), rdn("2.5.4.3", cn));
    }
    private static byte[] rdn(String oid, String value) {
        return tlv(0x31, seq(oid(oid), tlv(0x0C, value.getBytes(StandardCharsets.UTF_8))));
    }
    private static byte[] time(Date date) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC")); c.setTime(date);
        boolean general = c.get(Calendar.YEAR) >= 2050;
        SimpleDateFormat f = new SimpleDateFormat(general ? "yyyyMMddHHmmss'Z'" : "yyMMddHHmmss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return tlv(general ? 0x18 : 0x17, f.format(date).getBytes(StandardCharsets.US_ASCII));
    }
    private static byte[] oid(String dotted) {
        String[] p = dotted.split("\\.");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(Integer.parseInt(p[0]) * 40 + Integer.parseInt(p[1]));
        for (int i = 2; i < p.length; i++) {
            long v = Long.parseLong(p[i]);
            byte[] tmp = new byte[10]; int n = 0;
            do { tmp[n++] = (byte) (v & 0x7F); v >>= 7; } while (v > 0);
            for (int j = n - 1; j >= 0; j--) out.write(tmp[j] | (j > 0 ? 0x80 : 0));
        }
        return tlv(0x06, out.toByteArray());
    }
    private static byte[] seq(byte[]... parts) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (byte[] p : parts) body.write(p, 0, p.length);
        return tlv(0x30, body.toByteArray());
    }
    private static byte[] tlv(int tag, byte[] value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        int n = value.length;
        if (n < 128) out.write(n);
        else if (n < 256) { out.write(0x81); out.write(n); }
        else if (n < 65536) { out.write(0x82); out.write(n >> 8); out.write(n & 0xFF); }
        else { out.write(0x83); out.write(n >> 16); out.write((n >> 8) & 0xFF); out.write(n & 0xFF); }
        out.write(value, 0, value.length);
        return out.toByteArray();
    }
    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < b.length; i++) { if (i > 0) sb.append(':'); sb.append(String.format(Locale.ROOT, "%02X", b[i] & 0xFF)); }
        return sb.toString();
    }
}
