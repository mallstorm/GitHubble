package app.githubble.mobile.controller;

import android.content.SharedPreferences;
import app.githubble.mobile.security.SecureStore;
import app.githubble.mobile.translation.TranslationCache;
import app.githubble.mobile.translation.TranslationEngine;

/** Account-independent local selection and encrypted cloud-provider settings. */
public final class TranslationController {
    private final SecureStore secure;
    private final SharedPreferences prefs;
    private final TranslationCache cache;
    private final TranslationEngine engine;

    public TranslationController(SecureStore secure,SharedPreferences prefs,
            TranslationCache cache,TranslationEngine engine){
        this.secure=secure;this.prefs=prefs;this.cache=cache;this.engine=engine;
    }

    public TranslationEngine.Config config(){
        String mode=secure.get("translator_provider");
        if(mode.isEmpty())mode="mlkit";
        // Older versions stored cloud fields under translator_*. Preserve existing preferences.
        return settings("auto".equals(mode)?"mlkit":mode);
    }

    public TranslationEngine.Config cloudConfig(){
        String provider=secure.get("translator_cloud_provider");
        if(provider.isEmpty()){
            String previous=secure.get("translator_provider");
            provider=isCloud(previous)?previous:"libretranslate";
        }
        return settings(provider);
    }

    private TranslationEngine.Config settings(String provider){
        TranslationEngine.Config c=new TranslationEngine.Config();
        c.provider=provider;
        c.sourceLanguage=prefs.getString("translator_source_language","auto");
        c.chunkSize=Math.max(1400,Math.min(6500,
                prefs.getInt("translator_chunk_size",3600)));
        if(isCloud(provider)){
            c.endpoint=secure.get("translator_cloud_endpoint");
            if(c.endpoint.isEmpty())c.endpoint=secure.get("translator_endpoint");
            c.apiKey=secure.get("translator_cloud_api_key");
            if(c.apiKey.isEmpty())c.apiKey=secure.get("translator_api_key");
            c.model=secure.get("translator_cloud_model");
            if(c.model.isEmpty())c.model=secure.get("translator_model");
            if(c.endpoint.isEmpty()){
                if("deepl".equals(provider))c.endpoint="https://api-free.deepl.com/v2/translate";
                if("google".equals(provider))c.endpoint="https://translation.googleapis.com/language/translate/v2";
            }
        }
        return c;
    }

    private static boolean isCloud(String provider){
        return "deepl".equals(provider)||"google".equals(provider)||
                "libretranslate".equals(provider)||"openai".equals(provider)||
                "generic".equals(provider);
    }

    public TranslationCache cache(){return cache;}
    public TranslationEngine engine(){return engine;}
}
