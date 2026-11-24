package app.githubble.mobile.ui;

import android.graphics.Color;

/** One accent colour per programming language, shared by repository tiles and search cards. */
public final class LanguageColors {
    private static final String[][] COLORS = {{"Kotlin","7755CC"},{"Java","C27A38"},{"Go","159E97"},{"C++","4C7BBB"},
            {"Python","4E9B70"},{"JavaScript","D4B21E"},{"TypeScript","3178C6"},{"Swift","E8683F"},
            {"Rust","B5654A"},{"C#","6A4FB0"},{"C","5B6B7A"},{"HTML","E0572F"},{"CSS","3D6FD1"},
            {"Shell","4DA64D"},{"Dart","1FA5C9"},{"PHP","7A7FB8"},{"Ruby","C13B3B"},{"Lua","2F3F94"},
            {"MQL5","B8892B"},{"MQL4","B8892B"},{"Kotlin Script","7755CC"},{"Objective-C++","438EFF"},
            {"Objective-C","438EFF"},{"Jupyter Notebook","DA5B0B"},{"Vue","41B883"},{"PowerShell","3C7DC4"},
            {"GDScript","355570"},{"Scala","C22D40"},{"Zig","EC915C"},{"Elixir","6E4A7E"}};

    private LanguageColors() {}

    /** Colour for the language, or {@code fallback} when it is unknown or empty. */
    public static int of(String language, int fallback) {
        if (language != null) for (String[] item : COLORS) if (item[0].equalsIgnoreCase(language)) return 0xFF000000 | Integer.parseInt(item[1], 16);
        return fallback;
    }

    public static int mix(int a, int b, float w) {
        return Color.rgb((int) (Color.red(a) * (1 - w) + Color.red(b) * w),
                (int) (Color.green(a) * (1 - w) + Color.green(b) * w),
                (int) (Color.blue(a) * (1 - w) + Color.blue(b) * w));
    }
}
