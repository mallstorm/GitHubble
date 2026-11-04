package app.githubble.mobile.backup;

import android.content.SharedPreferences;
import app.githubble.mobile.tracking.TrackingDatabase;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Exports/imports user-visible local state. OAuth tokens, API keys and signing data are excluded by design. */
public final class PortableSettingsManager {
    private static final List<String> ALLOWED_SETTINGS=Arrays.asList(
            "theme","launcher_icon","translator_auto","translator_provider","translator_endpoint","translator_model",
            "tracking_interval_hours","tracking_notify_new_repositories","tracking_notify_releases","tracking_notify_popularity",
            "tracking_notify_pushes","tracking_notify_status","tracking_star_threshold","tracking_fork_threshold",
            "tracking_release_check_limit","diff_mode","diff_collapse_context",
            "ui_card_density","repositories_grid","repositories_sort","voice_search_language");
    private final SharedPreferences prefs;private final TrackingDatabase tracking;
    public PortableSettingsManager(SharedPreferences prefs,TrackingDatabase tracking){this.prefs=prefs;this.tracking=tracking;}

    public JSONObject exportData()throws Exception{
        JSONObject root=new JSONObject();root.put("format","githubble-portable-settings");root.put("version",1);root.put("created_at",java.time.Instant.now().toString());
        JSONArray favorites=new JSONArray();for(String item:prefs.getStringSet("favorites",new LinkedHashSet<>()))favorites.put(item);root.put("favorites",favorites);
        JSONArray developers=new JSONArray();for(TrackingDatabase.Developer d:tracking.getDevelopers(false)){JSONObject o=new JSONObject();o.put("login",d.login);o.put("enabled",d.enabled);o.put("added_at",d.addedAt);developers.put(o);}root.put("tracked_developers",developers);
        JSONObject settings=new JSONObject();for(String key:ALLOWED_SETTINGS){Object value=prefs.getAll().get(key);if(value instanceof String||value instanceof Boolean||value instanceof Integer||value instanceof Long||value instanceof Float)settings.put(key,value);}root.put("settings",settings);
        root.put("excluded","OAuth token, translation API key, account profile cache, diagnostics and signing material");return root;
    }

    public ImportResult importData(JSONObject root)throws Exception{
        if(!"githubble-portable-settings".equals(root.optString("format")))throw new IllegalArgumentException("Это не резервная копия GitHubble");
        int favoritesAdded=0,developersAdded=0,settingsApplied=0;
        Set<String> favorites=new LinkedHashSet<>(prefs.getStringSet("favorites",new LinkedHashSet<>()));JSONArray fav=root.optJSONArray("favorites");if(fav!=null)for(int i=0;i<fav.length();i++){String value=fav.optString(i,"").trim();if(value.contains("/")&&favorites.add(value))favoritesAdded++;}
        SharedPreferences.Editor editor=prefs.edit().putStringSet("favorites",favorites);JSONObject settings=root.optJSONObject("settings");if(settings!=null)for(String key:ALLOWED_SETTINGS){if(!settings.has(key))continue;Object value=settings.get(key);if(value instanceof Boolean)editor.putBoolean(key,(Boolean)value);else if(value instanceof Integer)editor.putInt(key,(Integer)value);else if(value instanceof Long)editor.putLong(key,(Long)value);else if(value instanceof Double)editor.putFloat(key,((Double)value).floatValue());else editor.putString(key,String.valueOf(value));settingsApplied++;}editor.apply();
        JSONArray developers=root.optJSONArray("tracked_developers");if(developers!=null)for(int i=0;i<developers.length();i++){JSONObject o=developers.optJSONObject(i);if(o==null)continue;String login=o.optString("login","").trim();if(login.isEmpty())continue;TrackingDatabase.Developer d=tracking.getDeveloper(login);if(d==null){d=new TrackingDatabase.Developer();d.login=login;d.addedAt=o.optLong("added_at",System.currentTimeMillis());d.enabled=o.optBoolean("enabled",true);tracking.upsertDeveloper(d);developersAdded++;}}
        return new ImportResult(favoritesAdded,developersAdded,settingsApplied);
    }

    public static final class ImportResult {public final int favoritesAdded,developersAdded,settingsApplied;ImportResult(int f,int d,int s){favoritesAdded=f;developersAdded=d;settingsApplied=s;}}
}
