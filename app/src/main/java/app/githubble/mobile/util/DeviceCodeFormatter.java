package app.githubble.mobile.util;

public final class DeviceCodeFormatter {
    private DeviceCodeFormatter() {}
    public static String normalize(String value) {
        if (value == null) return "";
        StringBuilder out = new StringBuilder(8);
        for (int i=0;i<value.length();i++) {
            char c=value.charAt(i);
            if (Character.isLetterOrDigit(c)) out.append(Character.toUpperCase(c));
        }
        return out.toString();
    }
}
