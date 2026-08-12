package app.githubble.mobile.translation;

import java.util.Locale;

/** Lightweight offline language detector used only for UI routing, never for security decisions. */
public final class LanguageDetector {
    public static final class Result {
        public final String code;
        public final String displayName;
        public final float confidence;
        Result(String code, String displayName, float confidence) {
            this.code = code; this.displayName = displayName; this.confidence = confidence;
        }
    }

    private LanguageDetector() {}

    public static Result detect(String text) {
        if (text == null || text.trim().isEmpty()) return new Result("und", "Язык не определён", 0f);
        int cyr = 0, latin = 0, cjk = 0, kana = 0, hangul = 0, arabic = 0, letters = 0;
        for (int i = 0; i < text.length();) {
            int cp = text.codePointAt(i); i += Character.charCount(cp);
            if (!Character.isLetter(cp)) continue;
            letters++;
            if ((cp >= 0x0400 && cp <= 0x052F) || (cp >= 0x2DE0 && cp <= 0x2DFF)) cyr++;
            else if ((cp >= 0x4E00 && cp <= 0x9FFF) || (cp >= 0x3400 && cp <= 0x4DBF)) cjk++;
            else if ((cp >= 0x3040 && cp <= 0x30FF)) kana++;
            else if ((cp >= 0xAC00 && cp <= 0xD7AF)) hangul++;
            else if ((cp >= 0x0600 && cp <= 0x06FF)) arabic++;
            else if ((cp >= 'A' && cp <= 'Z') || (cp >= 'a' && cp <= 'z') || (cp >= 0x00C0 && cp <= 0x024F)) latin++;
        }
        if (letters == 0) return new Result("und", "Технический текст", 0.3f);
        int max = Math.max(Math.max(Math.max(cyr, latin), Math.max(cjk, kana)), Math.max(hangul, arabic));
        float confidence = Math.min(0.99f, Math.max(0.35f, max / (float) Math.max(1, letters)));
        if (cyr == max) return containsUkrainian(text)
                ? new Result("uk", "Украинский", confidence)
                : new Result("ru", "Русский/кириллица", confidence);
        if (kana == max) return new Result("ja", "Японский", confidence);
        if (hangul == max) return new Result("ko", "Корейский", confidence);
        if (cjk == max) return new Result("zh", "Китайский", confidence);
        if (arabic == max) return new Result("ar", "Арабский", confidence);
        if (latin == max) return new Result("en", guessLatinName(text), confidence);
        return new Result("und", "Смешанный язык", confidence);
    }

    public static boolean needsRussianTranslation(String text) {
        Result r = detect(text);
        return !"ru".equals(r.code) && !"und".equals(r.code);
    }

    private static boolean containsUkrainian(String text) {
        String t = text.toLowerCase(new Locale("uk"));
        return t.indexOf('і') >= 0 || t.indexOf('ї') >= 0 || t.indexOf('є') >= 0 || t.indexOf('ґ') >= 0;
    }

    private static String guessLatinName(String text) {
        String lower = " " + text.toLowerCase(Locale.ROOT) + " ";
        int english = score(lower, " the ", " and ", " with ", " this ", " for ", " from ");
        int spanish = score(lower, " el ", " la ", " para ", " con ", " una ", " los ");
        int french = score(lower, " le ", " la ", " les ", " avec ", " pour ", " une ");
        int german = score(lower, " der ", " die ", " das ", " und ", " mit ", " für ");
        if (spanish > english && spanish >= french && spanish >= german) return "Испанский";
        if (french > english && french >= german) return "Французский";
        if (german > english) return "Немецкий";
        return "Английский/латиница";
    }

    private static int score(String text, String... tokens) {
        int score = 0;
        for (String token : tokens) if (text.contains(token)) score++;
        return score;
    }
}
