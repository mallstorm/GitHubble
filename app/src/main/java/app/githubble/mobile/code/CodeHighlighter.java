package app.githubble.mobile.code;

import android.graphics.Color;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Small dependency-free highlighter for source previews. */
public final class CodeHighlighter {
    private CodeHighlighter() {}

    public static Spannable highlight(String code, String fileName, int textColor, int mutedColor, int accentColor, int searchColor, String query) {
        SpannableStringBuilder out = new SpannableStringBuilder(code == null ? "" : code);
        String ext = extension(fileName);
        apply(out, Pattern.compile("(?m)//.*$|#(?![0-9A-Fa-f]{3,8}\\b).*$|/\\*(?s:.*?)\\*/"), mutedColor, false);
        apply(out, Pattern.compile("\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'"), Color.rgb(180, 92, 48), false);
        apply(out, Pattern.compile("\\b(?:true|false|null|None|nil|undefined|[0-9]+(?:\\.[0-9]+)?)\\b"), Color.rgb(126, 76, 196), false);
        String keywords = keywords(ext);
        if (!keywords.isEmpty()) apply(out, Pattern.compile("\\b(?:" + keywords + ")\\b"), accentColor, true);
        if (query != null && !query.trim().isEmpty()) {
            Pattern p = Pattern.compile(Pattern.quote(query.trim()), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
            Matcher m = p.matcher(out.toString());
            while (m.find()) out.setSpan(new BackgroundColorSpan(searchColor), m.start(), m.end(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return out;
    }

    private static void apply(SpannableStringBuilder out, Pattern pattern, int color, boolean bold) {
        Matcher m = pattern.matcher(out.toString());
        while (m.find()) {
            out.setSpan(new ForegroundColorSpan(color), m.start(), m.end(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (bold) out.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), m.start(), m.end(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    private static String extension(String name) {
        if (name == null) return ""; int dot = name.lastIndexOf('.'); return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String keywords(String ext) {
        switch (ext) {
            case "java": case "kt": case "kts": return "class|interface|enum|record|public|private|protected|static|final|abstract|void|int|long|double|float|boolean|char|byte|short|new|return|if|else|for|while|do|switch|case|break|continue|try|catch|finally|throw|throws|extends|implements|package|import|this|super|fun|val|var|object|when|is|in|as|data|sealed|suspend|override";
            case "js": case "ts": case "tsx": case "jsx": return "const|let|var|function|class|extends|import|export|default|return|if|else|for|while|switch|case|break|continue|try|catch|finally|throw|new|async|await|interface|type|enum|implements|public|private|protected|readonly|typeof|instanceof";
            case "py": return "def|class|import|from|as|return|if|elif|else|for|while|try|except|finally|raise|with|lambda|yield|async|await|global|nonlocal|pass|break|continue|in|is|not|and|or";
            case "c": case "h": case "cpp": case "cc": case "hpp": return "auto|break|case|char|const|continue|default|do|double|else|enum|extern|float|for|goto|if|inline|int|long|register|restrict|return|short|signed|sizeof|static|struct|switch|typedef|union|unsigned|void|volatile|while|class|namespace|template|public|private|protected|virtual|override|new|delete";
            case "go": return "break|default|func|interface|select|case|defer|go|map|struct|chan|else|goto|package|switch|const|fallthrough|if|range|type|continue|for|import|return|var";
            case "rs": return "as|break|const|continue|crate|else|enum|extern|false|fn|for|if|impl|in|let|loop|match|mod|move|mut|pub|ref|return|self|Self|static|struct|super|trait|true|type|unsafe|use|where|while|async|await|dyn";
            case "xml": case "html": return "html|head|body|div|span|script|style|manifest|application|activity|service|receiver|provider|uses-permission";
            case "json": case "yaml": case "yml": return "true|false|null";
            default: return "";
        }
    }
}
