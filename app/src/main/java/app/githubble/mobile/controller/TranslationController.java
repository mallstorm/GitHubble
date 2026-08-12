package app.githubble.mobile.controller;

import android.content.SharedPreferences;
import app.githubble.mobile.security.SecureStore;
import app.githubble.mobile.translation.TranslationCache;
import app.githubble.mobile.translation.TranslationEngine;

/** Translation configuration/cache orchestration extracted from MainActivity. */
public final class TranslationController {
    private final SecureStore secure; private final SharedPreferences prefs; private final TranslationCache cache; private final TranslationEngine engine;
    public TranslationController(SecureStore secure,SharedPreferences prefs,TranslationCache cache,TranslationEngine engine){this.secure=secure;this.prefs=prefs;this.cache=cache;this.engine=engine;}
    public TranslationEngine.Config config(){TranslationEngine.Config c=new TranslationEngine.Config();c.provider=secure.get("translator_provider");if(c.provider.isEmpty())c.provider="generic";c.endpoint=secure.get("translator_endpoint");c.apiKey=secure.get("translator_api_key");c.model=secure.get("translator_model");c.chunkSize=Math.max(1400,Math.min(6500,prefs.getInt("translator_chunk_size",3600)));return c;}
    public TranslationCache cache(){return cache;} public TranslationEngine engine(){return engine;}
}
