package app.githubble.mobile.security;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Stores OAuth tokens and API keys encrypted with an Android Keystore AES key. */
public final class SecureStore {
    private static final String KEY_ALIAS = "githubble_secure_store_v1";
    private static final String PREFS = "githubble_secure";
    private final SharedPreferences prefs;

    public SecureStore(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public synchronized void put(String name, String value) {
        if (value == null || value.isEmpty()) { remove(name); return; }
        try {
            SecretKey key = getOrCreateKey();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key);
            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            prefs.edit()
                .putString(name + ".iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                .putString(name + ".data", Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .apply();
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось зашифровать секрет", e);
        }
    }

    public synchronized String get(String name) {
        String ivText = prefs.getString(name + ".iv", null);
        String dataText = prefs.getString(name + ".data", null);
        if (ivText == null || dataText == null) return "";
        try {
            SecretKey key = getOrCreateKey();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key,
                new GCMParameterSpec(128, Base64.decode(ivText, Base64.NO_WRAP)));
            byte[] plain = cipher.doFinal(Base64.decode(dataText, Base64.NO_WRAP));
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            // Invalidated keystore keys must not leak or crash the app forever.
            remove(name);
            return "";
        }
    }

    public synchronized void remove(String name) {
        prefs.edit().remove(name + ".iv").remove(name + ".data").apply();
    }

    private SecretKey getOrCreateKey() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        KeyStore.Entry entry = store.getEntry(KEY_ALIAS, null);
        if (entry instanceof KeyStore.SecretKeyEntry) {
            return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build());
        return generator.generateKey();
    }
}
