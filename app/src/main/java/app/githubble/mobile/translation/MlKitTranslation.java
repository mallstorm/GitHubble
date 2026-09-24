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
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
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
        return translateSegments(Collections.singletonList(input),sourceCode,targetCode,null).get(0);
    }

    /**
     * One translator instance for all prose pieces in a document. Code, URLs,
     * Markdown tokens and their stand-in markers are never included in this list.
     */
    public static List<String> translateSegments(List<String> prose,String sourceCode,
                                                  String targetCode,
                                                  TranslationEngine.Progress progress)throws Exception{
        if(prose==null||prose.isEmpty())return new ArrayList<>();
        String from=TranslateLanguage.fromLanguageTag(sourceCode);
        String to=TranslateLanguage.fromLanguageTag(targetCode);
        if(from==null||to==null)
            throw new IllegalStateException("Языковая пара не поддерживается ML Kit.");
        if(from.equals(to))return new ArrayList<>(prose);
        // Check once per document, not before every Markdown/code boundary.
        if(!modelExists(from)||!modelExists(to))
            throw new IllegalStateException("Языковая модель отсутствует. Откройте Настройки → "+
                    "Перевод → Модели ML Kit и загрузите нужные языки по Wi-Fi.");
        TranslatorOptions options=new TranslatorOptions.Builder()
                .setSourceLanguage(from).setTargetLanguage(to).build();
        List<String> output=new ArrayList<>(prose.size());
        try(Translator translator=Translation.getClient(options)){
            for(int i=0;i<prose.size();i++){
                String segment=prose.get(i);
                if(segment==null||segment.trim().isEmpty()){
                    output.add(segment==null?"":segment);
                }else{
                    int fromIndex=0,toIndex=segment.length();
                    while(fromIndex<toIndex&&Character.isWhitespace(segment.charAt(fromIndex)))
                        fromIndex++;
                    while(toIndex>fromIndex&&Character.isWhitespace(segment.charAt(toIndex-1)))
                        toIndex--;
                    String translated=Tasks.await(translator.translate(
                            segment.substring(fromIndex,toIndex)),90,TimeUnit.SECONDS);
                    if(translated==null||translated.trim().isEmpty())
                        throw new IllegalStateException("Локальный переводчик вернул пустой текст.");
                    output.add(segment.substring(0,fromIndex)+translated+
                            segment.substring(toIndex));
                }
                if(progress!=null)progress.onProgress(i+1,prose.size());
            }
        }
        return output;
    }

    public static boolean modelExists(String language)throws Exception{
        TranslateRemoteModel model=new TranslateRemoteModel.Builder(language).build();
        return Boolean.TRUE.equals(Tasks.await(RemoteModelManager.getInstance()
                .isModelDownloaded(model),30,TimeUnit.SECONDS));
    }

    /** The ML Kit SDK is the source of truth for all supported language codes. */
    public static java.util.Set<String> supportedLanguages(){
        return new java.util.HashSet<>(TranslateLanguage.getAllLanguages());
    }

    /** One request for all installed translation models; do not poll each row separately. */
    public static java.util.Set<String> downloadedLanguages()throws Exception{
        java.util.Set<String> output=new java.util.HashSet<>();
        java.util.Set<TranslateRemoteModel> models=Tasks.await(
                RemoteModelManager.getInstance().getDownloadedModels(TranslateRemoteModel.class),
                45,TimeUnit.SECONDS);
        for(TranslateRemoteModel model:models)output.add(model.getLanguage());
        return output;
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
