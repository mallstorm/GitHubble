package app.githubble.mobile;

import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.os.SystemClock;
import app.githubble.mobile.ui.HomeScreen;
import java.io.*;
import java.lang.reflect.Method;

/** Actual theme selector, readable blue palette and exactly one enabled blue launcher alias. */
final class ThemeUiChecks {
    static void run(Instrumentation host,AgentChatPreviewActivity activity)throws Exception {
        android.content.SharedPreferences prefs=activity.getSharedPreferences("githubble",0);
        String density=prefs.getString("ui_card_density","compact");
        try{
            call(host,activity,"showSettingsInterface");call(host,activity,"chooseThemeDialog");
            ChangeReviewUiChecks.click(host,"Светлая — белая и зелёная");
            call(host,activity,"chooseThemeDialog");ChangeReviewUiChecks.click(host,"Светло-синяя — голубая и синяя");
            if(!"blue".equals(prefs.getString("theme","")))throw new AssertionError("Blue theme choice was not persisted");
            HomeScreen.Palette palette=palette(activity);
            if(Color.blue(palette.green)<=Color.green(palette.green)||Color.luminance(palette.background)<.8)throw new AssertionError("Blue light palette not applied");
            if(androidx.core.graphics.ColorUtils.calculateContrast(palette.text,palette.card)<4.5
                ||androidx.core.graphics.ColorUtils.calculateContrast(palette.muted,palette.card)<4.5)throw new AssertionError("Blue text has insufficient contrast");
            PackageManager pm=activity.getPackageManager();
            for(String alias:new String[]{"LightIcon","BeigeIcon","DarkIcon","BlueIcon"}){
                int state=pm.getComponentEnabledSetting(new ComponentName(activity.getPackageName(),"app.githubble.mobile."+alias));
                int expected=alias.equals("BlueIcon")?PackageManager.COMPONENT_ENABLED_STATE_ENABLED:PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
                if(state!=expected)throw new AssertionError("Wrong launcher state: "+alias+" = "+state);
            }
            Method icon=MainActivity.class.getDeclaredMethod("currentIconResource");icon.setAccessible(true);
            if((Integer)icon.invoke(activity)!=R.mipmap.ic_launcher_blue)throw new AssertionError("Blue app icon not selected");
            host.waitForIdleSync();capture(host,activity,"blue-settings");
            prefs.edit().putString("ui_card_density","spacious").commit();call(host,activity,"showHome");
            host.waitForIdleSync();SystemClock.sleep(200);capture(host,activity,"blue-home-spacious");
        }finally{
            prefs.edit().putString("ui_card_density",density).commit();host.runOnMainSync(activity::showEmpty);
        }
    }
    static HomeScreen.Palette palette(Activity activity){
        try{Method method=MainActivity.class.getDeclaredMethod("settingsPalette");method.setAccessible(true);return (HomeScreen.Palette)method.invoke(activity);}
        catch(Exception e){throw new AssertionError(e);}
    }
    private static void call(Instrumentation host,Activity activity,String name){host.runOnMainSync(()->{
        try{Method method=MainActivity.class.getDeclaredMethod(name);method.setAccessible(true);method.invoke(activity);}
        catch(Exception e){throw new AssertionError(e);}
    });host.waitForIdleSync();}
    private static void capture(Instrumentation host,Activity activity,String name)throws Exception {
        Bitmap image=host.getUiAutomation().takeScreenshot();if(image==null)throw new AssertionError("No screenshot");File dir=activity.getExternalFilesDir("agent-ui");dir.mkdirs();
        try(FileOutputStream out=new FileOutputStream(new File(dir,name+".png"))){image.compress(Bitmap.CompressFormat.PNG,100,out);}finally{image.recycle();}
    }
}
