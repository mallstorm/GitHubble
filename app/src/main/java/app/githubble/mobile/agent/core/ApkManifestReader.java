package app.githubble.mobile.agent.core;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Minimal reader for the binary AndroidManifest.xml inside an APK: just the identity the
 * Supervisor must confirm (package, versionCode, versionName, debuggable). Strict bounds
 * checks; any inconsistency throws so a damaged manifest is never reported as verified.
 */
public final class ApkManifestReader {
    public static final class Identity {
        public String packageName = "", versionName = "";
        public long versionCode = -1;
        public boolean debuggable;
    }

    public static final class ParseException extends Exception {
        public ParseException(String m) { super(m); }
    }

    private static final int RES_XML = 0x0003, STRING_POOL = 0x0001, START_ELEMENT = 0x0102;
    private static final int TYPE_STRING = 0x03, TYPE_INT_DEC = 0x10, TYPE_INT_HEX = 0x11, TYPE_BOOL = 0x12;

    private ApkManifestReader() {}

    public static Identity read(byte[] data) throws ParseException {
        try {
            ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            if (data.length < 8 || (b.getShort(0) & 0xffff) != RES_XML) throw new ParseException("Не бинарный AndroidManifest");
            int total = Math.min(b.getInt(4), data.length);
            int pos = b.getShort(2) & 0xffff;
            String[] strings = null;
            Identity id = new Identity();
            boolean sawManifest = false;
            while (pos + 8 <= total) {
                int type = b.getShort(pos) & 0xffff, header = b.getShort(pos + 2) & 0xffff, size = b.getInt(pos + 4);
                if (size < 8 || pos + size > total) throw new ParseException("Повреждён чанк манифеста");
                if (type == STRING_POOL) {
                    strings = pool(b, pos, size);
                } else if (type == START_ELEMENT) {
                    if (strings == null) throw new ParseException("Нет таблицы строк");
                    int body = pos + header;
                    String name = str(strings, b.getInt(body + 4));
                    int attrStart = b.getShort(body + 8) & 0xffff, attrSize = b.getShort(body + 10) & 0xffff;
                    int count = b.getShort(body + 12) & 0xffff;
                    if (attrSize < 20) throw new ParseException("Неверный размер атрибута");
                    boolean manifest = "manifest".equals(name), application = "application".equals(name);
                    if (manifest || application) {
                        for (int i = 0; i < count; i++) {
                            int a = body + attrStart + i * attrSize;
                            if (a + 20 > pos + size) throw new ParseException("Атрибут вне чанка");
                            String attr = str(strings, b.getInt(a + 4));
                            int raw = b.getInt(a + 8), dataType = b.get(a + 15) & 0xff, value = b.getInt(a + 16);
                            if (manifest) {
                                sawManifest = true;
                                if ("package".equals(attr)) id.packageName = text(strings, raw, dataType, value);
                                else if ("versionName".equals(attr)) id.versionName = text(strings, raw, dataType, value);
                                else if ("versionCode".equals(attr) && (dataType == TYPE_INT_DEC || dataType == TYPE_INT_HEX))
                                    id.versionCode = value & 0xffffffffL;
                            } else if ("debuggable".equals(attr) && dataType == TYPE_BOOL) {
                                id.debuggable = value != 0;
                            }
                        }
                        if (manifest && !application) { /* continue to <application> */ }
                    }
                    if (application) break;
                }
                pos += size;
            }
            if (!sawManifest || id.packageName.isEmpty()) throw new ParseException("В манифесте нет <manifest package>");
            return id;
        } catch (IndexOutOfBoundsException | java.nio.BufferUnderflowException e) {
            throw new ParseException("Манифест обрезан");
        }
    }

    private static String text(String[] strings, int raw, int dataType, int value) {
        if (raw >= 0) return str(strings, raw);
        if (dataType == TYPE_STRING) return str(strings, value);
        return String.valueOf(value);
    }

    private static String str(String[] strings, int index) {
        return index < 0 || index >= strings.length ? "" : strings[index];
    }

    private static String[] pool(ByteBuffer b, int pos, int size) throws ParseException {
        int header = b.getShort(pos + 2) & 0xffff;
        int count = b.getInt(pos + 8), flags = b.getInt(pos + 16), stringsStart = b.getInt(pos + 20);
        if (count < 0 || count > 200_000 || header + count * 4L > size) throw new ParseException("Неверная таблица строк");
        boolean utf8 = (flags & 0x100) != 0;
        String[] out = new String[count];
        for (int i = 0; i < count; i++) {
            int off = pos + stringsStart + b.getInt(pos + header + i * 4);
            if (off < pos || off >= pos + size) throw new ParseException("Строка вне таблицы");
            out[i] = utf8 ? utf8(b, off, pos + size) : utf16(b, off, pos + size);
        }
        return out;
    }

    private static String utf16(ByteBuffer b, int off, int end) throws ParseException {
        int len = b.getShort(off) & 0xffff;
        int start = off + 2;
        if ((len & 0x8000) != 0) { len = ((len & 0x7fff) << 16) | (b.getShort(off + 2) & 0xffff); start += 2; }
        if (start + len * 2L > end) throw new ParseException("Строка UTF-16 выходит за границу");
        char[] chars = new char[len];
        for (int i = 0; i < len; i++) chars[i] = b.getChar(start + i * 2);
        return new String(chars);
    }

    private static String utf8(ByteBuffer b, int off, int end) throws ParseException {
        int p = off;
        int chars = b.get(p++) & 0xff;
        if ((chars & 0x80) != 0) p++;
        int len = b.get(p++) & 0xff;
        if ((len & 0x80) != 0) { len = ((len & 0x7f) << 8) | (b.get(p++) & 0xff); }
        if (p + len > end) throw new ParseException("Строка UTF-8 выходит за границу");
        byte[] raw = new byte[len];
        for (int i = 0; i < len; i++) raw[i] = b.get(p + i);
        return new String(raw, StandardCharsets.UTF_8);
    }
}
