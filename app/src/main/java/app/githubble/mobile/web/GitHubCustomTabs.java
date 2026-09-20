package app.githubble.mobile.web;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;

import java.util.List;

/**
 * Small dependency-free Android Custom Tabs launcher.
 *
 * GitHubble prefers Chrome, then any installed browser exposing the standard
 * Custom Tabs service. If no provider exists, it safely falls back to the
 * normal browser ACTION_VIEW flow.
 */
public final class GitHubCustomTabs {
    private static final String CUSTOM_TABS_SERVICE_ACTION =
        "android.support.customtabs.action.CustomTabsService";
    private static final String EXTRA_SESSION =
        "android.support.customtabs.extra.SESSION";
    private static final String EXTRA_TOOLBAR_COLOR =
        "android.support.customtabs.extra.TOOLBAR_COLOR";
    private static final String EXTRA_SECONDARY_TOOLBAR_COLOR =
        "android.support.customtabs.extra.SECONDARY_TOOLBAR_COLOR";
    private static final String EXTRA_ENABLE_URLBAR_HIDING =
        "android.support.customtabs.extra.ENABLE_URLBAR_HIDING";
    private static final String EXTRA_TITLE_VISIBILITY =
        "android.support.customtabs.extra.TITLE_VISIBILITY";
    private static final String EXTRA_SHARE_MENU_ITEM =
        "android.support.customtabs.extra.SHARE_MENU_ITEM";
    private static final String EXTRA_COLOR_SCHEME =
        "androidx.browser.customtabs.extra.COLOR_SCHEME";

    private static final int SHOW_PAGE_TITLE = 1;
    private static final int COLOR_SCHEME_LIGHT = 1;
    private static final int COLOR_SCHEME_DARK = 2;

    private static final String[] PREFERRED_PACKAGES = {
        "com.android.chrome",
        "com.chrome.beta",
        "com.chrome.dev",
        "com.chrome.canary"
    };

    private GitHubCustomTabs() {}

    /**
     * @return true when a real Custom Tab provider was used; false when a
     * normal browser fallback was used.
     */
    public static boolean open(Context context, Uri uri, int toolbarColor, boolean darkMode) {
        String provider = findProvider(context.getPackageManager());
        if (provider != null) {
            Intent intent = new Intent(Intent.ACTION_VIEW, uri);
            intent.setPackage(provider);

            // The presence of the standard session extra identifies this as a
            // Custom Tab even when GitHubble does not pre-bind a session.
            Bundle sessionBundle = new Bundle();
            sessionBundle.putBinder(EXTRA_SESSION, null);
            intent.putExtras(sessionBundle);
            intent.putExtra(EXTRA_TOOLBAR_COLOR, toolbarColor);
            intent.putExtra(EXTRA_SECONDARY_TOOLBAR_COLOR, toolbarColor);
            intent.putExtra(EXTRA_ENABLE_URLBAR_HIDING, true);
            intent.putExtra(EXTRA_TITLE_VISIBILITY, SHOW_PAGE_TITLE);
            intent.putExtra(EXTRA_SHARE_MENU_ITEM, false);
            intent.putExtra(EXTRA_COLOR_SCHEME, darkMode ? COLOR_SCHEME_DARK : COLOR_SCHEME_LIGHT);
            intent.putExtra(Intent.EXTRA_REFERRER,
                Uri.parse("android-app://" + context.getPackageName()));
            context.startActivity(intent);
            return true;
        }

        Intent fallback = new Intent(Intent.ACTION_VIEW, uri);
        fallback.addCategory(Intent.CATEGORY_BROWSABLE);
        context.startActivity(fallback);
        return false;
    }

    private static String findProvider(PackageManager packageManager) {
        Intent service = new Intent(CUSTOM_TABS_SERVICE_ACTION);
        for (String packageName : PREFERRED_PACKAGES) {
            service.setPackage(packageName);
            if (packageManager.resolveService(service, 0) != null) return packageName;
        }

        service.setPackage(null);
        List<ResolveInfo> providers = packageManager.queryIntentServices(service, 0);
        if (providers == null) return null;
        for (ResolveInfo info : providers) {
            if (info != null && info.serviceInfo != null && info.serviceInfo.packageName != null) {
                return info.serviceInfo.packageName;
            }
        }
        return null;
    }
}
