package app.githubble.mobile.ui;

import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import app.githubble.mobile.R;

/**
 * RC4.2: one navigation contract for the five root destinations.
 * A root tab never adds itself to the previous tab's back stack.
 * Settings are reachable from the Profile screen, not duplicated as a tab.
 */
public final class BottomNavigationBar {
    public static final String[] DESTINATIONS =
        {"Главная", "Репозитории", "Поиск", "Активность", "Профиль"};

    public interface Actions {
        void openHome();
        void openRepositories();
        void openSearch();
        void openActivity();
        void openProfile();
    }

    private static final int[] ICONS = {
        R.drawable.ic_home, R.drawable.ic_repository, R.drawable.ic_search,
        R.drawable.ic_bell, R.drawable.ic_profile
    };

    private BottomNavigationBar() {}

    public static boolean isRoot(String title) {
        for (String destination : DESTINATIONS) if (destination.equals(title)) return true;
        return false;
    }

    public static LinearLayout create(Activity activity, String currentTitle, int surface,
                                      int subtle, int accent, int muted, Actions actions) {
        LinearLayout nav = new LinearLayout(activity);
        nav.setGravity(Gravity.CENTER);
        nav.setPadding(dp(activity,4),dp(activity,3),dp(activity,4),dp(activity,2));
        nav.setBackgroundColor(surface);
        for (int index=0; index<DESTINATIONS.length; index++) {
            final int destination=index;
            final boolean selected=DESTINATIONS[index].equals(currentTitle);
            LinearLayout cell=new LinearLayout(activity);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER);
            cell.setMinimumHeight(dp(activity,48));
            cell.setFocusable(true);
            cell.setClickable(true);
            cell.setContentDescription(DESTINATIONS[index]+(selected?", текущая вкладка":""));
            ImageView symbol=new ImageView(activity);
            symbol.setImageResource(ICONS[index]);
            symbol.setColorFilter(selected?accent:muted);
            symbol.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            symbol.setPadding(dp(activity,11),dp(activity,4),dp(activity,11),dp(activity,4));
            GradientDrawable pill=new GradientDrawable();
            pill.setColor(selected?mix(subtle,accent,.14f):Color.TRANSPARENT);
            pill.setCornerRadius(dp(activity,18));
            symbol.setBackground(pill);
            cell.addView(symbol,new LinearLayout.LayoutParams(dp(activity,48),dp(activity,32)));
            TextView label=new TextView(activity);
            label.setText(DESTINATIONS[index]);
            label.setTextSize(activity.getResources().getConfiguration().fontScale>=1.25f?11:12);
            label.setTextColor(selected?accent:muted);
            label.setTypeface(null,selected?Typeface.BOLD:Typeface.NORMAL);
            label.setGravity(Gravity.CENTER);
            label.setSingleLine(true);
            cell.addView(label,new LinearLayout.LayoutParams(-1,dp(activity,24)));
            cell.setOnClickListener(v->{
                switch(destination) {
                    case 0: actions.openHome(); break;
                    case 1: actions.openRepositories(); break;
                    case 2: actions.openSearch(); break;
                    case 3: actions.openActivity(); break;
                    case 4: actions.openProfile(); break;
                    default: break;
                }
            });
            nav.addView(cell,new LinearLayout.LayoutParams(0,-1,1f));
        }
        return nav;
    }

    private static int dp(Activity activity,int value) {
        return (int)(value*activity.getResources().getDisplayMetrics().density+.5f);
    }

    private static int mix(int a,int b,float weight) {
        return Color.rgb((int)(Color.red(a)*(1-weight)+Color.red(b)*weight),
                (int)(Color.green(a)*(1-weight)+Color.green(b)*weight),
                (int)(Color.blue(a)*(1-weight)+Color.blue(b)*weight));
    }
}
