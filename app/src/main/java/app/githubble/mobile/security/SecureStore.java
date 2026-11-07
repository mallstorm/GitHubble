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
public final class SecureStore implements app.githubble.mobile.network.GitHubSession.Source {
  public static final Object SESSION_LOCK = new Object();
  private static long sessionGeneration;
  private static final String KEY_ALIAS = "githubble_secure_store_v1";
  private static final String PREFS = "githubble_secure";
  private final SharedPreferences prefs;

  public SecureStore(Context context) {
    prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
  }

  public void put(String name, String value) {
    synchronized (SESSION_LOCK) {
      if (("github_token".equals(name) || "github_login".equals(name))
          && !get(name).equals(value == null ? "" : value)) sessionGeneration++;
      if (value == null || value.isEmpty()) {
        remove(name);
        return;
      }
      try {
        SecretKey key = getOrCreateKey();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
        prefs
            .edit()
            .putString(name + ".iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
            .putString(name + ".data", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .apply();
      } catch (Exception e) {
        throw new IllegalStateException("Не удалось зашифровать секрет", e);
      }
    }
  }

  public String get(String name) {
    synchronized (SESSION_LOCK) {
      String ivText = prefs.getString(name + ".iv", null);
      String dataText = prefs.getString(name + ".data", null);
      if (ivText == null || dataText == null) return "";
      try {
        SecretKey key = getOrCreateKey();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(
            Cipher.DECRYPT_MODE,
            key,
            new GCMParameterSpec(128, Base64.decode(ivText, Base64.NO_WRAP)));
        byte[] plain = cipher.doFinal(Base64.decode(dataText, Base64.NO_WRAP));
        return new String(plain, StandardCharsets.UTF_8);
      } catch (Exception e) {
        // Invalidated keystore keys must not leak or crash the app forever.
        remove(name);
        return "";
      }
    }
  }

  public void remove(String name) {
    synchronized (SESSION_LOCK) {
      if (("github_token".equals(name) || "github_login".equals(name))
          && prefs.contains(name + ".data")) sessionGeneration++;
      prefs.edit().remove(name + ".iv").remove(name + ".data").apply();
    }
  }

  @Override
  public app.githubble.mobile.network.GitHubSession capture() {
    synchronized (SESSION_LOCK) {
      return new app.githubble.mobile.network.GitHubSession(
          get("github_token"), get("github_login"), sessionGeneration);
    }
  }

  @Override
  public boolean isCurrent(app.githubble.mobile.network.GitHubSession session) {
    synchronized (SESSION_LOCK) {
      return session.generation == sessionGeneration && session.token.equals(get("github_token"));
    }
  }

  @Override
  public void reauthorization(
      app.githubble.mobile.network.GitHubSession session, boolean required) {
    synchronized (SESSION_LOCK) {
      if (!isCurrent(session)) return;
      if (required) put("github_reauth_required", "true");
      else remove("github_reauth_required");
    }
  }

  @Override
  public boolean commitIfCurrent(
      app.githubble.mobile.network.GitHubSession session, Runnable action) {
    synchronized (SESSION_LOCK) {
      if (!isCurrent(session)) return false;
      action.run();
      return true;
    }
  }

  /** Complete reset includes every AI/provider/plugin secret, not just GitHub keys. */
  public void clearAll() {
    synchronized (SESSION_LOCK) {
      sessionGeneration++;
      if (!prefs.edit().clear().commit())
        throw new IllegalStateException("Не удалось удалить секреты");
    }
  }

  private SecretKey getOrCreateKey() throws Exception {
    KeyStore store = KeyStore.getInstance("AndroidKeyStore");
    store.load(null);
    KeyStore.Entry entry = store.getEntry(KEY_ALIAS, null);
    if (entry instanceof KeyStore.SecretKeyEntry) {
      return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
    }
    KeyGenerator generator =
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
    generator.init(
        new KeyGenParameterSpec.Builder(
                KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build());
    return generator.generateKey();
  }
}
