package app.githubble.mobile.translation;

import android.content.Context;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.common.model.RemoteModelManager;
import com.google.mlkit.nl.languageid.LanguageIdentification;
import com.google.mlkit.nl.languageid.LanguageIdentifier;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.TranslateRemoteModel;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Native/offline Google ML Kit backend. All blocking methods run on the app's IO worker. */
public final class MlKitTranslation {
    private MlKitTranslation(){}

    private static String language(String source) throws Exception {
        String natural=source.replaceAll("(?s)```.*?```", " ")
                .replaceAll("`[^`\\n]+`", " ")
                .replaceAll("https?://\\S+", " ")
                .replaceAll("(?m)^\\s{0,3}[#>*-]\\s*", "");
        try(LanguageIdentifier identifier=LanguageIdentification.getClient()){
            String detected=Tasks.await(identifier.identifyLanguage(natural),30,TimeUnit.SECONDS);
            if(detected==null||"und".equals(detected))
                throw new IllegalStateException("Язык не определён. Выберите исходный язык в настройках перевода.");
            String tag=TranslateLanguage.fromLanguageTag(detected);
            if(tag==null)
                throw new IllegalStateException("ML Kit пока не поддерживает язык "+detected+
                        ". Выберите облачный переводчик.");
            return tag;
        }
    }

    public static String detectSource(String text,String override)throws Exception{
        String chosen=override==null?"auto":override.trim().toLowerCase(Locale.ROOT);
        if(!chosen.isEmpty()&&!"auto".equals(chosen)){
            String tag=TranslateLanguage.fromLanguageTag(chosen);
            if(tag==null)throw new IllegalArgumentException("ML Kit не поддерживает язык: "+chosen);
            return tag;
        }
        return language(text);
    }

    public static String translate(String input,String sourceCode,String targetCode)throws Exception{
        String from=TranslateLanguage.fromLanguageTag(sourceCode);
        String to=TranslateLanguage.fromLanguageTag(targetCode);
        if(from==null||to==null)
            throw new IllegalStateException("Языковая пара не поддерживается ML Kit.");
        if(from.equals(to))return input;
        TranslatorOptions options=new TranslatorOptions.Builder()
                .setSourceLanguage(from).setTargetLanguage(to).build();
        try(Translator translator=Translation.getClient(options)){
            // Never trigger a surprise mobile-data model download from a page.
            if(!modelExists(from)||!modelExists(to))
                throw new IllegalStateException("Языковая модель отсутствует. Откройте Настройки → "+
                        "Перевод → Модели ML Kit и загрузите нужные языки по Wi-Fi.");
            return Tasks.await(translator.translate(input),90,TimeUnit.SECONDS);
        }
    }

    public static boolean modelExists(String language)throws Exception{
        TranslateRemoteModel model=new TranslateRemoteModel.Builder(language).build();
        return Boolean.TRUE.equals(Tasks.await(RemoteModelManager.getInstance()
                .isModelDownloaded(model),30,TimeUnit.SECONDS));
    }

    /** Explicit user action only: install missing models using Wi-Fi. */
    public static void downloadModel(String language)throws Exception{
        String normalized=TranslateLanguage.fromLanguageTag(language);
        if(normalized==null)throw new IllegalArgumentException("Язык не поддерживается: "+language);
        TranslateRemoteModel model=new TranslateRemoteModel.Builder(normalized).build();
        Tasks.await(RemoteModelManager.getInstance().download(model,
                new DownloadConditions.Builder().requireWifi().build()),15,TimeUnit.MINUTES);
    }
    public static void deleteModel(String language)throws Exception{
        String normalized=TranslateLanguage.fromLanguageTag(language);
        if(normalized==null)throw new IllegalArgumentException("Язык не поддерживается");
        Tasks.await(RemoteModelManager.getInstance().deleteDownloadedModel(
                new TranslateRemoteModel.Builder(normalized).build()),60,TimeUnit.SECONDS);
    }
}
