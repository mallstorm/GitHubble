package app.githubble.mobile.dictation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Catalogue of speech-to-text services the dictation module can talk to. */
public final class DictationProviders {
    public static final String SYSTEM = "system", GROQ = "groq", OPENAI = "openai", YANDEX = "yandex", GOOGLE = "google", CUSTOM = "custom";

    public static final class Info {
        public final String id, title, subtitle, icon, defaultModel, help;
        public final String[] models;
        public final boolean needsKey, endpoint, folder;
        public final int chunkSeconds;
        Info(String id, String title, String subtitle, String icon, boolean needsKey, String defaultModel, String[] models,
             boolean endpoint, boolean folder, int chunkSeconds, String help) {
            this.id = id; this.title = title; this.subtitle = subtitle; this.icon = icon; this.needsKey = needsKey;
            this.defaultModel = defaultModel; this.models = models; this.endpoint = endpoint; this.folder = folder;
            this.chunkSeconds = chunkSeconds; this.help = help;
        }
    }

    /** code → title; "auto" lets the service detect the language. */
    public static final String[][] LANGUAGES = {
            {"auto", "Автоопределение"}, {"ru-RU", "Русский"}, {"en-US", "English"}, {"uk-UA", "Українська"},
            {"de-DE", "Deutsch"}, {"fr-FR", "Français"}, {"es-ES", "Español"}, {"it-IT", "Italiano"},
            {"pt-BR", "Português"}, {"pl-PL", "Polski"}, {"tr-TR", "Türkçe"}, {"kk-KZ", "Қазақша"}, {"uz-UZ", "Oʻzbekcha"}};

    private static final List<Info> ALL;
    static {
        List<Info> list = new ArrayList<>();
        list.add(new Info(SYSTEM, "Системное распознавание", "Служба Android / Google на устройстве. Без ключа", "system-mic", false, "", new String[0], false, false, 0,
                "Работает через голосовой ввод Google, установленный на телефоне."));
        list.add(new Info(GROQ, "Whisper · Groq", "whisper-large-v3-turbo: очень быстро, есть бесплатный лимит", "groq", true, "whisper-large-v3-turbo",
                new String[]{"whisper-large-v3-turbo", "whisper-large-v3"}, false, false, 300,
                "Ключ создаётся в console.groq.com → API Keys. Если Groq уже подключён как сервис ИИ, ключ подхватится сам."));
        list.add(new Info(OPENAI, "Whisper · OpenAI", "gpt-4o-mini-transcribe и Whisper", "openai", true, "gpt-4o-mini-transcribe",
                new String[]{"gpt-4o-mini-transcribe", "gpt-4o-transcribe", "whisper-1"}, false, false, 300,
                "Ключ создаётся на platform.openai.com → API keys. Если OpenAI API уже подключён, ключ подхватится сам."));
        list.add(new Info(YANDEX, "Яндекс SpeechKit", "Лучший для русского языка, короткие записи режутся на части", "yandex", true, "general", new String[0], false, true, 28,
                "API-ключ сервисного аккаунта с ролью ai.speechkit-stt.user (Yandex Cloud). Каталог (folderId) нужен только для IAM-токена."));
        list.add(new Info(GOOGLE, "Google Cloud Speech-to-Text", "Облачный API Google, много языков", "google", true, "", new String[0], false, false, 55,
                "API-ключ из Google Cloud Console с включённым Cloud Speech-to-Text API (ключ Gemini из AI Studio не подходит)."));
        list.add(new Info(CUSTOM, "Свой Whisper (OpenAI-совместимый)", "faster-whisper-server, whisper.cpp, LocalAI, любой свой endpoint", "custom-whisper", false, "whisper-1",
                new String[]{"whisper-1"}, true, false, 300,
                "Адрес вида http://192.168.1.5:8000/v1 — к нему добавится /audio/transcriptions. Ключ нужен, только если сервер его требует."));
        ALL = Collections.unmodifiableList(list);
    }

    private DictationProviders() { }

    public static List<Info> all() { return ALL; }

    public static Info get(String id) {
        for (Info info : ALL) if (info.id.equals(id)) return info;
        return ALL.get(0);
    }

    public static String languageTitle(String code) {
        for (String[] l : LANGUAGES) if (l[0].equals(code)) return "auto".equals(code) ? l[1] : l[1] + " (" + code + ")";
        return code == null || code.isEmpty() ? "Русский (ru-RU)" : code;
    }
}
