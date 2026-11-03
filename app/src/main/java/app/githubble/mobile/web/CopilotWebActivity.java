package app.githubble.mobile.web;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/** GitHub-owned web page; no JS bridge, OAuth token injection or SDK. */
public final class CopilotWebActivity extends Activity {
    public static final String URL="https://github.com/copilot";
    private WebView web;
    public static boolean isAllowedTopLevel(Uri uri){
        return uri!=null && "https".equalsIgnoreCase(uri.getScheme())
            && "github.com".equalsIgnoreCase(uri.getHost());
    }
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        LinearLayout bar=new LinearLayout(this);bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(8,6,8,6);
        bar.addView(action("←",()->finish()),new LinearLayout.LayoutParams(64,52));
        TextView title=new TextView(this);title.setText("Copilot Web");
        title.setTextColor(Color.DKGRAY);title.setTextSize(17);
        title.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(title,new LinearLayout.LayoutParams(0,52,1));
        bar.addView(action("В браузере",()->openBrowser(Uri.parse(URL))));
        root.addView(bar);
        TextView note=new TextView(this);
        note.setText("Официальный GitHub. Для входа через сторонний OAuth может понадобиться системный браузер.");
        note.setTextColor(Color.DKGRAY);note.setTextSize(11);note.setPadding(12,5,12,9);
        root.addView(note);
        web=new WebView(this);
        WebSettings settings=web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest req){
                if(!req.isForMainFrame() || isAllowedTopLevel(req.getUrl()))return false;
                openBrowser(req.getUrl());return true;
            }
            @Override public void onReceivedError(WebView view,WebResourceRequest req,android.webkit.WebResourceError err){
                if(req.isForMainFrame())Toast.makeText(CopilotWebActivity.this,
                    "Copilot Web не загрузился. Откройте страницу в браузере.",Toast.LENGTH_LONG).show();
            }
        });
        root.addView(web,new LinearLayout.LayoutParams(-1,0,1));
        setContentView(root);
        web.loadUrl(URL);
    }
    private TextView action(String label,Runnable click){
        TextView v=new TextView(this);v.setText(label);v.setTextSize(15);
        v.setGravity(Gravity.CENTER);v.setTextColor(Color.rgb(45,70,98));
        v.setPadding(12,5,12,5);v.setOnClickListener(x->click.run());return v;
    }
    private void openBrowser(Uri url){
        if(url==null||!"https".equalsIgnoreCase(url.getScheme()))return;
        try{startActivity(new Intent(Intent.ACTION_VIEW,url));}
        catch(Exception e){Toast.makeText(this,"Браузер недоступен",Toast.LENGTH_SHORT).show();}
    }
    @Override public void onBackPressed(){if(web!=null&&web.canGoBack())web.goBack();else super.onBackPressed();}
    @Override protected void onDestroy(){
        if(web!=null){web.stopLoading();web.loadUrl("about:blank");web.destroy();web=null;}
        super.onDestroy();
    }
}
