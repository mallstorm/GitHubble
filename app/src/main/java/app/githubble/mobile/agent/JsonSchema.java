package app.githubble.mobile.agent;

import org.json.JSONArray;
import org.json.JSONObject;

/** Deliberately bounded schema subset used by the host-owned tool catalog. Unknown keywords aren't model input. */
final class JsonSchema {
    private JsonSchema() {}
    static void validate(JSONObject schema, Object value) { validate(schema, value, "$", 0); }
    private static void validate(JSONObject s, Object value, String path, int depth) {
        if (depth > 12) throw new IllegalArgumentException("Слишком глубокие аргументы");
        for(String keyword:new String[]{"$ref","anyOf","allOf","oneOf","not","if","then","else"})
            if(s.has(keyword))throw new IllegalArgumentException("Составная схема пока не поддерживается: "+keyword);
        String type = s.optString("type");
        boolean valid;
        switch (type) {
            case "object": valid = value instanceof JSONObject; break;
            case "array": valid = value instanceof JSONArray; break;
            case "string": valid = value instanceof String; break;
            case "boolean": valid = value instanceof Boolean; break;
            case "integer": valid = value instanceof Number && Double.isFinite(((Number)value).doubleValue())
                && ((Number)value).doubleValue() == Math.rint(((Number)value).doubleValue()); break;
            case "number": valid = value instanceof Number && Double.isFinite(((Number)value).doubleValue()); break;
            default: throw new IllegalStateException("Неподдерживаемая схема: " + type);
        }
        if (!valid) throw new IllegalArgumentException(path + ": нужен " + type);
        JSONArray choices = s.optJSONArray("enum");
        if (choices != null) {
            boolean found = false;
            for (int i=0;i<choices.length();i++) if (value.equals(choices.opt(i))) found = true;
            if (!found) throw new IllegalArgumentException(path + ": недопустимое значение");
        }
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject)value, properties = s.optJSONObject("properties");
            JSONArray required = s.optJSONArray("required");
            if (required != null) for (int i=0;i<required.length();i++)
                if (!object.has(required.optString(i))) throw new IllegalArgumentException(path + ": отсутствует " + required.optString(i));
            for (String key : UnifiedAgentStore.keys(object)) {
                if (properties == null || !properties.has(key)) {
                    if (!s.optBoolean("additionalProperties", true)) throw new IllegalArgumentException(path + ": лишний аргумент " + key);
                } else validate(properties.optJSONObject(key), object.opt(key), path + "." + key, depth+1);
            }
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray)value;
            if (array.length() < s.optInt("minItems",0) || array.length() > s.optInt("maxItems",100))
                throw new IllegalArgumentException(path + ": превышен размер массива");
            for (int i=0;i<array.length();i++) validate(s.optJSONObject("items"), array.opt(i), path+"["+i+"]", depth+1);
        } else if (value instanceof String) {
            String text = (String)value;
            if (text.length() < s.optInt("minLength",0) || text.length() > s.optInt("maxLength",12000))
                throw new IllegalArgumentException(path + ": неверная длина");
            if (s.has("pattern") && !text.matches(s.optString("pattern"))) throw new IllegalArgumentException(path + ": неверный формат");
        } else if (value instanceof Number) {
            double n = ((Number)value).doubleValue();
            if (n < s.optDouble("minimum",-Double.MAX_VALUE) || n > s.optDouble("maximum",Double.MAX_VALUE))
                throw new IllegalArgumentException(path + ": вне диапазона");
        }
    }
}
