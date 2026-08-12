package app.githubble.mobile.state;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import org.json.JSONObject;

/** Keeps only lightweight route/argument/scroll state; entities stay in the persistent cache. */
public final class ScreenStateStore {
    private static final String KEY_ROUTE="ui_route", KEY_ARGS="ui_args", KEY_SCROLL="ui_scroll";
    private final SharedPreferences prefs;
    private String route="home", args="{}"; private int scroll;
    public ScreenStateStore(Context context){prefs=context.getSharedPreferences("githubble_ui_state",Context.MODE_PRIVATE);}
    public void remember(String route, JSONObject args){String nextRoute=route==null?"home":route;String nextArgs=args==null?"{}":args.toString();if(!nextRoute.equals(this.route)||!nextArgs.equals(this.args))scroll=0;this.route=nextRoute;this.args=nextArgs;prefs.edit().putString(KEY_ROUTE,this.route).putString(KEY_ARGS,this.args).putInt(KEY_SCROLL,scroll).apply();}
    public void setScroll(int value){scroll=Math.max(0,value);prefs.edit().putInt(KEY_SCROLL,scroll).apply();}
    public String route(){return route;} public JSONObject args(){try{return new JSONObject(args);}catch(Exception e){return new JSONObject();}} public int scroll(){return scroll;}
    public void save(Bundle out){out.putString(KEY_ROUTE,route);out.putString(KEY_ARGS,args);out.putInt(KEY_SCROLL,scroll);}
    public void restore(Bundle in){if(in!=null){route=in.getString(KEY_ROUTE,prefs.getString(KEY_ROUTE,"home"));args=in.getString(KEY_ARGS,prefs.getString(KEY_ARGS,"{}"));scroll=in.getInt(KEY_SCROLL,prefs.getInt(KEY_SCROLL,0));}else{route=prefs.getString(KEY_ROUTE,"home");args=prefs.getString(KEY_ARGS,"{}");scroll=prefs.getInt(KEY_SCROLL,0);}}
    public void clear(){route="home";args="{}";scroll=0;prefs.edit().clear().apply();}
}
