package app.githubble.mobile.repository;

import app.githubble.mobile.network.GitHubApiClient;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Settings → Custom properties: values of the organization's property schema for one repository. */
public final class CustomProperties {
    private CustomProperties() {}

    public static final class Property {
        public final String name, type, description; public final boolean required; public final List<String> options; public String value;
        Property(String name, String type, String description, boolean required, List<String> options, String value) {
            this.name = name; this.type = type; this.description = description; this.required = required; this.options = options; this.value = value;
        }
    }

    public static String owner(String repo) {
        String r = RepoSecrets.checkRepo(repo);
        return r.substring(0, r.indexOf('/'));
    }

    static List<Property> merge(JSONArray schema, JSONArray values) {
        java.util.Map<String, String> current = new java.util.HashMap<>();
        for (int i = 0; i < values.length(); i++) {
            JSONObject v = values.optJSONObject(i);
            if (v == null) continue;
            Object raw = v.opt("value");
            String text = raw == null || raw == JSONObject.NULL ? "" : (raw instanceof JSONArray ? join((JSONArray) raw) : String.valueOf(raw));
            current.put(v.optString("property_name"), text);
        }
        List<Property> out = new ArrayList<>();
        for (int i = 0; i < schema.length(); i++) {
            JSONObject s = schema.optJSONObject(i);
            if (s == null) continue;
            List<String> options = new ArrayList<>();
            JSONArray allowed = s.optJSONArray("allowed_values");
            if (allowed != null) for (int j = 0; j < allowed.length(); j++) options.add(allowed.optString(j));
            String name = s.optString("property_name");
            String def = s.isNull("default_value") ? "" : String.valueOf(s.opt("default_value"));
            out.add(new Property(name, s.optString("value_type", "string"), s.isNull("description") ? "" : s.optString("description", ""), s.optBoolean("required"), options, current.containsKey(name) ? current.get(name) : def));
        }
        return out;
    }

    static String join(JSONArray a) { StringBuilder b = new StringBuilder(); for (int i = 0; i < a.length(); i++) { if (i > 0) b.append(", "); b.append(a.optString(i)); } return b.toString(); }

    public static List<Property> load(GitHubApiClient api, String repo) throws Exception {
        JSONArray schema = Gh.array(api, "https://api.github.com/orgs/" + owner(repo) + "/properties/schema");
        JSONArray values = Gh.array(api, Gh.repo(repo) + "/properties/values");
        return merge(schema, values);
    }

    static Object convert(Property p, String raw) {
        String v = raw == null ? "" : raw.trim();
        if (v.isEmpty()) return JSONObject.NULL;
        if ("multi_select".equals(p.type)) { JSONArray a = new JSONArray(); for (String part : v.split("\\s*,\\s*")) if (!part.isEmpty()) a.put(part); return a; }
        return v;
    }

    static JSONObject body(List<Property> props) throws Exception {
        JSONArray list = new JSONArray();
        for (Property p : props) {
            String v = p.value == null ? "" : p.value.trim();
            if (p.required && v.isEmpty()) throw new IllegalArgumentException("Свойство «" + p.name + "» обязательно");
            if ("true_false".equals(p.type) && !v.isEmpty() && !v.equals("true") && !v.equals("false")) throw new IllegalArgumentException("«" + p.name + "»: true или false");
            if (("single_select".equals(p.type)) && !v.isEmpty() && !p.options.contains(v)) throw new IllegalArgumentException("«" + p.name + "»: выберите одно из допустимых значений");
            list.put(new JSONObject().put("property_name", p.name).put("value", convert(p, v)));
        }
        return new JSONObject().put("properties", list);
    }

    public static void save(GitHubApiClient api, String repo, List<Property> props) throws Exception {
        api.patchJson(Gh.repo(repo) + "/properties/values", body(props));
    }
}
