package app.githubble.mobile.dictation;

import android.content.SharedPreferences;
import app.githubble.mobile.security.SecureStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Which dictation service is primary, its language and keys. Keys live in the Keystore-backed vault. */
public final class DictationSettings {
    /** Lets Groq / OpenAI dictation reuse a key the user already added as an AI connection. */
    public interface KeyBorrower { String borrow(String providerId); }

    private final SharedPreferences prefs;
    private final SecureStore vault;
    private final KeyBorrower borrower;

    public DictationSettings(SharedPreferences prefs, SecureStore vault, KeyBorrower borrower) {
        this.prefs = prefs; this.vault = vault; this.borrower = borrower;
    }

    public String provider() {
        String id = prefs.getString("dictation_provider", DictationProviders.SYSTEM);
        return DictationProviders.get(id).id;
    }
    public void setProvider(String id) { prefs.edit().putString("dictation_provider", DictationProviders.get(id).id).apply(); }

    public String language() {
        return prefs.getString("voice_search_language", "ru".equalsIgnoreCase(Locale.getDefault().getLanguage()) ? "ru-RU" : "en-US");
    }
    public void setLanguage(String code) { prefs.edit().putString("voice_search_language", code).apply(); }

    public boolean fallback() { return prefs.getBoolean("dictation_fallback", true); }
    public void setFallback(boolean on) { prefs.edit().putBoolean("dictation_fallback", on).apply(); }

    private String keyName(String id) { return "dictation_key_" + id; }

    public String ownKey(String id) { return vault.get(keyName(id)); }
    public void setKey(String id, String key) {
        if (key == null || key.trim().isEmpty()) vault.remove(keyName(id)); else vault.put(keyName(id), key.trim());
    }
    public void clearKey(String id) { vault.remove(keyName(id)); }

    /** Own key first, then a key borrowed from an AI connection. */
    public String key(String id) {
        String own = ownKey(id);
        if (!own.isEmpty()) return own;
        try { String b = borrower == null ? "" : borrower.borrow(id); return b == null ? "" : b; } catch (RuntimeException e) { return ""; }
    }
    /** "own", "connection" or "none". */
    public String keySource(String id) {
        if (!ownKey(id).isEmpty()) return "own";
        return key(id).isEmpty() ? "none" : "connection";
    }

    public String model(String id) {
        String m = prefs.getString("dictation_model_" + id, "");
        return m.isEmpty() ? DictationProviders.get(id).defaultModel : m;
    }
    public void setModel(String id, String model) { prefs.edit().putString("dictation_model_" + id, model == null ? "" : model.trim()).apply(); }

    /** Endpoint for the custom Whisper server, folder id for Yandex. */
    public String extra(String id) { return prefs.getString("dictation_extra_" + id, ""); }
    public void setExtra(String id, String value) { prefs.edit().putString("dictation_extra_" + id, value == null ? "" : value.trim()).apply(); }

    public boolean configured(String id) {
        DictationProviders.Info info = DictationProviders.get(id);
        if (!info.needsKey && !info.endpoint) return true;
        if (info.endpoint && extra(id).isEmpty()) return false;
        return !info.needsKey || !key(id).isEmpty();
    }

    /** Services to try in order: the primary one first, then every other configured cloud service, the system recogniser last. */
    public List<String> order() {
        List<String> out = new ArrayList<>();
        String primary = provider();
        out.add(primary);
        if (!fallback()) return out;
        for (DictationProviders.Info info : DictationProviders.all())
            if (!info.id.equals(primary) && !DictationProviders.SYSTEM.equals(info.id) && configured(info.id)) out.add(info.id);
        return out;
    }
}
