package app.githubble.mobile.search;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Offline Russian technical-query planner for GitHub Search.
 * It never rewrites GitHub qualifiers (language:, stars:, repo:, topic:, etc.).
 */
public final class SmartSearchPlanner {
    private static final Pattern TOKEN = Pattern.compile("\\\"[^\\\"]+\\\"|\\S+");
    private static final Pattern QUALIFIER = Pattern.compile("^-?[A-Za-z][A-Za-z0-9_-]*:.*$");
    private static final Pattern CYRILLIC = Pattern.compile(".*[А-Яа-яЁё].*");

    private static final LinkedHashMap<String, String[]> PHRASES = new LinkedHashMap<>();
    private static final Map<String, String[]> WORDS = new HashMap<>();
    private static final Set<String> STOP = new HashSet<>();

    static {
        // Longest / most specific phrases first.
        phrase("открытый исходный код", "open source", "source code");
        phrase("искусственный интеллект", "artificial intelligence", "machine learning");
        phrase("машинное обучение", "machine learning", "ml");
        phrase("нейронная сеть", "neural network", "deep learning");
        phrase("нейронные сети", "neural networks", "deep learning");
        phrase("облачное хранилище", "cloud storage", "file storage");
        phrase("облачного хранилища", "cloud storage", "file storage");
        phrase("облачные хранилища", "cloud storage", "file storage");
        phrase("облачный диск", "cloud drive", "cloud storage");
        phrase("база данных", "database", "data storage");
        phrase("исходный код", "source code", "codebase");
        phrase("мобильное приложение", "mobile app", "android app");
        phrase("мобильные приложения", "mobile apps", "android apps");
        phrase("клиент телеграм", "telegram client", "telegram app");
        phrase("телеграм клиент", "telegram client", "telegram app");
        phrase("перехват потока", "stream capture", "media stream");
        phrase("видео поток", "video stream", "media stream");
        phrase("торговый советник", "expert advisor", "trading bot");
        phrase("умный дом", "smart home", "home automation");
        phrase("распознавание речи", "speech recognition", "speech to text");
        phrase("компьютерное зрение", "computer vision", "image recognition");
        phrase("файловый менеджер", "file manager", "file browser");
        phrase("менеджер загрузок", "download manager", "downloader");
        phrase("двухфакторная авторизация", "two factor authentication", "2fa");
        phrase("двухфакторная аутентификация", "two factor authentication", "2fa");
        phrase("локальная сеть", "local network", "lan");
        phrase("удаленный доступ", "remote access", "remote control");
        phrase("удалённый доступ", "remote access", "remote control");
        phrase("открытый api", "open api", "public api");

        word("аккаунт", "account", "profile");
        word("аккаунты", "accounts", "profiles");
        word("аккаунтов", "accounts", "profiles");
        word("несколько", "multiple", "multi");
        word("нескольких", "multiple", "multi");
        word("множество", "multiple", "multi");
        word("объединение", "aggregation", "unified");
        word("объединить", "aggregate", "combine");
        word("единый", "unified", "single");
        word("приложение", "app", "application", "client");
        word("приложения", "apps", "applications", "clients");
        word("приложений", "apps", "applications", "clients");
        word("клиент", "client", "app");
        word("сервер", "server", "backend");
        word("хранилище", "storage", "file storage", "cloud storage");
        word("хранилища", "storage", "file storage", "cloud storage");
        word("хранилищ", "storage", "file storage", "cloud storage");
        word("облако", "cloud", "cloud storage");
        word("облачный", "cloud", "cloud storage");
        word("облачное", "cloud", "cloud storage");
        word("диск", "drive", "storage");
        word("файл", "file", "document");
        word("файлы", "files", "documents");
        word("файлов", "files", "documents");
        word("папка", "folder", "directory");
        word("папки", "folders", "directories");
        word("папок", "folders", "directories");
        word("авторизация", "authentication", "oauth", "login");
        word("авторизации", "authentication", "oauth", "login");
        word("аутентификация", "authentication", "oauth", "login");
        word("аутентификации", "authentication", "oauth", "login");
        word("вход", "login", "authentication");
        word("регистрация", "registration", "signup");
        word("регистрации", "registration", "signup");
        word("ошибка", "bug", "issue", "error");
        word("ошибки", "bugs", "issues", "errors");
        word("ошибку", "bug", "issue", "error");
        word("ошибок", "bugs", "issues", "errors");
        word("исправление", "fix", "bugfix");
        word("поиск", "search", "finder");
        word("поиска", "search", "finder");
        word("перевод", "translation", "translator");
        word("перевода", "translation", "translator");
        word("русский", "russian", "ru");
        word("русского", "russian", "ru");
        word("сообщения", "messages", "messaging");
        word("сообщений", "messages", "messaging");
        word("сообщение", "message", "messaging");
        word("чат", "chat", "messenger");
        word("мессенджер", "messenger", "chat");
        word("телеграм", "telegram", "telegram client");
        word("телеграмм", "telegram", "telegram client");
        word("вконтакте", "vk", "vkontakte");
        word("видео", "video", "media");
        word("аудио", "audio", "media");
        word("поток", "stream", "streaming");
        word("потока", "stream", "streaming");
        word("перехват", "capture", "intercept");
        word("загрузчик", "downloader", "download manager");
        word("скачивание", "download", "downloader");
        word("плеер", "player", "media player");
        word("проигрыватель", "player", "media player");
        word("архив", "archive", "backup");
        word("резервная", "backup", "restore");
        word("копия", "backup", "copy");
        word("синхронизация", "synchronization", "sync");
        word("советник", "expert advisor", "trading bot");
        word("торговый", "trading", "finance");
        word("индикатор", "indicator", "technical analysis");
        word("прошивка", "firmware", "rom");
        word("прошивки", "firmware", "rom");
        word("модем", "modem", "router");
        word("модема", "modem", "router");
        word("роутер", "router", "gateway");
        word("маршрутизатор", "router", "gateway");
        word("сеть", "network", "networking");
        word("сети", "network", "networking");
        word("сетей", "network", "networking");
        word("локальный", "local", "offline");
        word("локальная", "local", "offline");
        word("офлайн", "offline", "local");
        word("агент", "agent", "ai agent");
        word("агента", "agent", "ai agent");
        word("агенты", "agents", "ai agents");
        word("модель", "model", "ai model");
        word("модели", "model", "ai model");
        word("нейросеть", "neural network", "ai");
        word("нейросети", "neural networks", "ai");
        word("искусственный", "artificial", "ai");
        word("интеллект", "intelligence", "ai");
        word("распознавание", "recognition", "detection");
        word("изображение", "image", "picture");
        word("изображений", "images", "pictures");
        word("камера", "camera", "vision");
        word("голос", "voice", "speech");
        word("текст", "text", "document");
        word("токен", "token", "credential");
        word("токена", "token", "credential");
        word("токены", "tokens", "credentials");
        word("обработка", "handling", "processing");
        word("обработки", "handling", "processing");
        word("обработчик", "handler", "processor");
        word("функция", "function", "method");
        word("функции", "functions", "methods");
        word("метод", "method", "function");
        word("метода", "method", "function");
        word("класс", "class", "type");
        word("класса", "class", "type");
        word("редактор", "editor", "ide");
        word("код", "code", "source code");
        word("кода", "code", "source code");
        word("репозиторий", "repository", "repo");
        word("репозитория", "repository", "repo");
        word("репозиториев", "repositories", "repos");
        word("репозитории", "repositories", "repos");
        word("библиотека", "library", "sdk");
        word("библиотеки", "libraries", "sdk");
        word("библиотек", "libraries", "sdk");
        word("инструмент", "tool", "utility");
        word("инструменты", "tools", "utilities");
        word("инструментов", "tools", "utilities");
        word("безопасность", "security", "privacy");
        word("шифрование", "encryption", "crypto");
        word("пароль", "password", "credentials");
        word("пароли", "passwords", "credentials");
        word("уведомления", "notifications", "alerts");
        word("уведомлений", "notifications", "alerts");
        word("уведомление", "notification", "alert");
        word("календарь", "calendar", "schedule");
        word("задачи", "tasks", "todo");
        word("задача", "task", "todo");
        word("заметки", "notes", "notepad");
        word("карта", "map", "maps");
        word("навигация", "navigation", "gps");
        word("погода", "weather", "forecast");
        word("новости", "news", "feed");
        word("аналитика", "analytics", "analysis");
        word("мониторинг", "monitoring", "observability");
        word("сканер", "scanner", "scan");
        word("портов", "ports", "port scanner");
        word("порт", "port", "network port");
        word("домен", "domain", "dns");
        word("доменов", "domains", "dns");
        word("прокси", "proxy", "tunnel");
        word("туннель", "tunnel", "vpn");
        word("рут", "root", "rooted");
        word("рутованный", "rooted", "root");
        word("андроид", "android", "mobile");
        word("виндовс", "windows", "win32");
        word("линукс", "linux", "unix");

        Collections.addAll(STOP,
                "для","на","в","во","и","или","к","ко","по","из","от","до","у","о","об","про","под","над","при","с","со",
                "это","этот","эта","эти","такой","такая","нужен","нужна","нужно","ищу","найти","сделать","реализовать","готовый","готовое");
    }

    private SmartSearchPlanner() {}

    public static final class Plan {
        public final String original;
        public final boolean smartRequested;
        public final boolean hasCyrillic;
        public final String semanticOriginal;
        public final String qualifiers;
        public final List<String> variants;
        public final Set<String> rankingTerms;

        private Plan(String original, boolean smartRequested, boolean hasCyrillic, String semanticOriginal,
                     String qualifiers, List<String> variants, Set<String> rankingTerms) {
            this.original = original;
            this.smartRequested = smartRequested;
            this.hasCyrillic = hasCyrillic;
            this.semanticOriginal = semanticOriginal;
            this.qualifiers = qualifiers;
            this.variants = Collections.unmodifiableList(variants);
            this.rankingTerms = Collections.unmodifiableSet(rankingTerms);
        }

        public boolean expanded() { return smartRequested && hasCyrillic && variants.size() > 1; }
        public String primary() { return variants.isEmpty() ? original : variants.get(0); }
    }

    public static Plan plan(String raw, boolean smart) {
        String original = raw == null ? "" : raw.trim().replaceAll("\\s+", " ");
        Split split = splitQualifiers(original);
        boolean cyr = CYRILLIC.matcher(split.semantic).matches();
        if (!smart || !cyr) {
            List<String> direct = new ArrayList<>();
            if (!original.isEmpty()) direct.add(original);
            return new Plan(original, smart, cyr, split.semantic, split.qualifiers, direct, rankingTokens(original));
        }

        Translation translated = translate(split.semantic);
        String suffix = split.qualifiers.isEmpty() ? "" : " " + split.qualifiers;
        LinkedHashSet<String> variants = new LinkedHashSet<>();
        addVariant(variants, translated.primary + suffix);
        addVariant(variants, translated.alternate + suffix);
        // Keep the original Russian wording as a deliberate third path for Russian README/description repositories.
        addVariant(variants, split.semantic + suffix);
        if (variants.isEmpty()) addVariant(variants, original);

        LinkedHashSet<String> ranking = new LinkedHashSet<>(rankingTokens(translated.primary));
        ranking.addAll(rankingTokens(translated.alternate));
        return new Plan(original, true, true, split.semantic, split.qualifiers,
                new ArrayList<>(variants).subList(0, Math.min(3, variants.size())), ranking);
    }

    private static final class Split {
        final String semantic, qualifiers;
        Split(String semantic, String qualifiers) { this.semantic = semantic; this.qualifiers = qualifiers; }
    }

    private static Split splitQualifiers(String query) {
        List<String> semantic = new ArrayList<>();
        List<String> qualifiers = new ArrayList<>();
        Matcher m = TOKEN.matcher(query == null ? "" : query);
        while (m.find()) {
            String token = m.group();
            String upper = token.toUpperCase(Locale.ROOT);
            if (QUALIFIER.matcher(token).matches() || "AND".equals(upper) || "OR".equals(upper) || "NOT".equals(upper)) qualifiers.add(token);
            else semantic.add(token);
        }
        return new Split(String.join(" ", semantic).trim(), String.join(" ", qualifiers).trim());
    }

    private static final class Translation {
        final String primary, alternate;
        Translation(String primary, String alternate) { this.primary = primary; this.alternate = alternate; }
    }

    private static Translation translate(String semantic) {
        String primary = semantic == null ? "" : semantic.toLowerCase(new Locale("ru"));
        String alternate = primary;

        // Phrase layer first, so "облачное хранилище" is not translated as two unrelated words.
        for (Map.Entry<String, String[]> e : PHRASES.entrySet()) {
            String source = e.getKey();
            if (containsPhrase(primary, source)) {
                primary = replacePhrase(primary, source, e.getValue()[0]);
                alternate = replacePhrase(alternate, source, e.getValue().length > 1 ? e.getValue()[1] : e.getValue()[0]);
            }
        }

        primary = translateWords(primary, false);
        alternate = translateWords(alternate, true);
        primary = clean(primary);
        alternate = clean(alternate);
        if (alternate.equals(primary)) alternate = buildAlternateFromEnglish(primary);
        return new Translation(primary, alternate);
    }

    private static String translateWords(String input, boolean alternate) {
        StringBuilder out = new StringBuilder();
        Matcher m = TOKEN.matcher(input);
        while (m.find()) {
            String token = m.group();
            String normalized = stripPunctuation(token).toLowerCase(new Locale("ru"));
            if (normalized.isEmpty()) continue;
            if (STOP.contains(normalized)) continue;
            String[] options = WORDS.get(normalized);
            String value;
            if (options != null) value = options[Math.min(alternate ? 1 : 0, options.length - 1)];
            else if (CYRILLIC.matcher(normalized).matches()) value = ""; // unknown Russian filler: omit rather than poison English search
            else value = token;
            if (!value.isEmpty()) {
                if (out.length() > 0) out.append(' ');
                out.append(value);
            }
        }
        return out.toString();
    }

    private static String buildAlternateFromEnglish(String primary) {
        if (primary == null || primary.isEmpty()) return "";
        String alt = " " + primary + " ";
        alt = alt.replace(" app ", " application ")
                .replace(" client ", " app ")
                .replace(" storage ", " file storage ")
                .replace(" authentication ", " oauth login ")
                .replace(" bug ", " issue ")
                .replace(" downloader ", " download manager ")
                .replace(" player ", " media player ")
                .replace(" search ", " finder ");
        return clean(alt);
    }

    private static Set<String> rankingTokens(String value) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (value == null) return out;
        Matcher m = TOKEN.matcher(value.toLowerCase(Locale.ROOT));
        while (m.find()) {
            String token = stripPunctuation(m.group());
            if (token.length() < 3 || QUALIFIER.matcher(token).matches()) continue;
            if ("and".equals(token) || "or".equals(token) || "not".equals(token)) continue;
            out.add(token);
        }
        return out;
    }

    private static boolean containsPhrase(String haystack, String phrase) {
        return (" " + haystack + " ").contains(" " + phrase + " ");
    }

    private static String replacePhrase(String value, String from, String to) {
        return clean((" " + value + " ").replace(" " + from + " ", " " + to + " "));
    }

    private static String stripPunctuation(String token) {
        if (token == null) return "";
        return token.replaceAll("^[\\p{Punct}«»]+|[\\p{Punct}«»]+$", "");
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }

    private static void addVariant(Set<String> variants, String value) {
        String normalized = clean(value);
        if (!normalized.isEmpty()) variants.add(normalized);
    }

    private static void phrase(String source, String primary, String alternate) {
        PHRASES.put(source, new String[]{primary, alternate});
    }

    private static void word(String source, String... targets) {
        WORDS.put(source, targets);
    }
}
