package app.githubble.mobile;

import android.app.*;
import android.graphics.*;
import android.os.SystemClock;
import android.view.*;
import android.widget.*;
import app.githubble.mobile.ui.RepositoriesScreen;
import org.json.*;
import java.io.*;
import java.util.*;

/** Real production repository controls: two phone columns, preserved search, ordering and actions. */
final class RepositoryUiChecks {
    static void run(Instrumentation host,Activity activity)throws Exception {
        JSONArray data=new JSONArray().put(repo("Beta",true,"Kotlin",9,"2025-01-01T00:00:00Z"))
            .put(repo("Alpha",false,"Python",50,"2024-01-01T00:00:00Z"))
            .put(repo("Gamma-long-repository-name",true,"Rust",3,"2026-01-01T00:00:00Z"));
        Dialog[] dialog={null};LinearLayout[] root={null};RepositoriesScreen[] screen={null};String[] opened={""},menu={""};
        try{
            host.runOnMainSync(()->{
                dialog[0]=new Dialog(activity);root[0]=new LinearLayout(activity);root[0].setOrientation(LinearLayout.VERTICAL);root[0].setPadding(16,16,16,16);root[0].setBackgroundColor(ThemeUiChecks.palette(activity).background);
                ScrollView scroll=new ScrollView(activity);scroll.addView(root[0]);dialog[0].setContentView(scroll);
                screen[0]=screen(activity,root[0],true,opened,menu);screen[0].render(data,"updated","Синхронизация: тестовые данные");
                dialog[0].getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
                dialog[0].show();dialog[0].getWindow().setLayout(-1,-1);
            });
            host.waitForIdleSync();SystemClock.sleep(150);
            host.runOnMainSync(()->{
                GridLayout grid=grid(root[0]);check(grid!=null&&grid.getColumnCount()==2,"Phone grid is not two columns");
                View a=grid.getChildAt(0),b=grid.getChildAt(1),c=grid.getChildAt(2);
                check(a.getTop()==b.getTop()&&a.getLeft()<b.getLeft()&&c.getTop()>a.getTop(),"Cards are not in two rows/columns");
                check(Math.abs(a.getWidth()-b.getWidth())<=2&&a.getWidth()>80,"Unequal or collapsed tile widths");
                check(text(root[0],"☰ Список")==null&&text(root[0],"▤ Плитки")==null,"Layout selector remains on repository screen");
                check(sort(root[0])==null,"Sort control remains on repository screen");
                TextView filter=(TextView)described(root[0],"Фильтр: приватные");
                check(filter.getHeight()<=activity.getResources().getDisplayMetrics().density*38,"Filter row is not compact");
                check(filter.getLayout()!=null&&filter.getLayout().getHeight()<=filter.getHeight(),"Compact filter text is clipped");
                described(root[0],"Фильтр: приватные").performClick();
                EditText search=find(root[0],EditText.class);search.setText("Beta");
                screen[0].updateData(data);
            });
            host.waitForIdleSync();
            host.runOnMainSync(()->{
                check("Beta".contentEquals(find(root[0],EditText.class).getText()),"Data refresh reset search");
                check(text(root[0],"Найдено: 1")!=null,"Data refresh reset privacy filter");
                GridLayout g=grid(root[0]);check(g.getChildCount()==2,"Single tile did not preserve a half-width column");
                g.getChildAt(0).performClick();check("demo/Beta".equals(opened[0]),"Tile opens wrong repository");
                described(root[0],"Действия с репозиторием Beta").performClick();check("demo/Beta".equals(menu[0]),"Tile menu opens wrong repository");
                find(root[0],EditText.class).setText("");described(root[0],"Фильтр по языку").performClick();
            });
            ChangeReviewUiChecks.click(host,"Rust");host.waitForIdleSync();
            host.runOnMainSync(()->{
                check(text(root[0],"Найдено: 1")!=null,"Language filter did not select Rust");
                described(root[0],"Фильтр: избранные").performClick();check(text(root[0],"Найдено: 0")!=null,"Language and favorites filters did not combine");
                described(root[0],"Все репозитории").performClick();check(text(root[0],"Найдено: 3")!=null,"All did not clear filters");
            });
            host.waitForIdleSync();saveScreenshot(host,activity,"grid");
            host.runOnMainSync(()->{
                root[0].removeAllViews();screen[0]=screen(activity,root[0],false,opened,menu);screen[0].render(data,"name","Синхронизация: тестовые данные");
                check(grid(root[0])==null,"List view unexpectedly renders a grid");
            });
            host.waitForIdleSync();saveScreenshot(host,activity,"list");
            host.runOnMainSync(()->{
                View a=described(root[0],"demo/Alpha, публичный репозиторий"),b=described(root[0],"demo/Beta, приватный репозиторий");
                int[] ap=new int[2],bp=new int[2];a.getLocationOnScreen(ap);b.getLocationOnScreen(bp);
                check(ap[0]==bp[0]&&bp[1]>ap[1],"List cards are not one per row");
                JSONArray many=new JSONArray();try{for(int i=0;i<25;i++)many.put(repo("Repo"+i,true,"Java",i,"2026-01-01T00:00:00Z"));}catch(Exception error){throw new AssertionError(error);}
                root[0].removeAllViews();screen[0]=screen(activity,root[0],true,opened,menu);screen[0].render(many,"name","Тест пагинации");
                check(grid(root[0]).getChildCount()==24,"First page does not contain 24 cards");
                described(root[0],"Загрузить следующие репозитории").performClick();
                check(grid(root[0]).getChildCount()==26,"Last odd card/pagination lost the grid placeholder");
            });
        }finally{host.runOnMainSync(()->{if(dialog[0]!=null)dialog[0].dismiss();});}
    }
    private static RepositoriesScreen screen(Activity activity,LinearLayout root,boolean grid,String[] opened,String[] menu){
        app.githubble.mobile.ui.HomeScreen.Palette p=ThemeUiChecks.palette(activity);
        return new RepositoriesScreen(activity,root,new RepositoriesScreen.Actions(){public void create(){}public void refresh(){}
            public void open(String full){opened[0]=full;}public void menu(JSONObject repo){menu[0]=repo.optString("full_name");}},
            p.card,p.text,p.muted,p.green,p.border,p.subtle,Collections.singleton("demo/Beta"),grid);
    }
    private static JSONObject repo(String name,boolean priv,String language,int stars,String date)throws Exception{return new JSONObject().put("name",name).put("full_name","demo/"+name).put("description","Описание проекта "+name).put("private",priv).put("language",language).put("stargazers_count",stars).put("updated_at",date);}
    private static View sort(View root){if(root.getContentDescription()!=null&&root.getContentDescription().toString().startsWith("Сортировка:"))return root;
        if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){View v=sort(((ViewGroup)root).getChildAt(i));if(v!=null)return v;}return null;}
    private static View described(View root,String value){if(value.contentEquals(root.getContentDescription()==null?"":root.getContentDescription()))return root;
        if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){View v=described(((ViewGroup)root).getChildAt(i),value);if(v!=null)return v;}return null;}
    private static TextView text(View root,String value){if(root instanceof TextView&&value.contentEquals(((TextView)root).getText()))return (TextView)root;
        if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){TextView v=text(((ViewGroup)root).getChildAt(i),value);if(v!=null)return v;}return null;}
    private static GridLayout grid(View root){return find(root,GridLayout.class);}
    private static <T extends View>T find(View root,Class<T> type){if(type.isInstance(root))return type.cast(root);if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){T v=find(((ViewGroup)root).getChildAt(i),type);if(v!=null)return v;}return null;}
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    private static void saveScreenshot(Instrumentation host,Activity activity,String mode)throws Exception{
        Bitmap bitmap=host.getUiAutomation().takeScreenshot();if(bitmap==null)throw new AssertionError("No screenshot");
        File dir=activity.getExternalFilesDir("agent-ui");dir.mkdirs();String scale=String.valueOf(activity.getResources().getConfiguration().fontScale);
        try(FileOutputStream out=new FileOutputStream(new File(dir,"repositories-"+mode+"-"+activity.getSharedPreferences("githubble",0).getString("theme","light")+"-"+scale+".png"))){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}finally{bitmap.recycle();}
    }
}
