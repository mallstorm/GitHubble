package app.githubble.mobile.accessibility;

import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.Switch;

/** Runtime guardrails for touch targets, focus order and missing content descriptions. */
public final class AccessibilityAudit {
    private AccessibilityAudit() { }

    public static void prepareTree(View root, int minimumTouchPx) {
        if (root == null) return;
        walk(root, minimumTouchPx);
    }

    private static void walk(View view, int minimumTouchPx) {
        boolean interactive = view.isClickable() || view instanceof Button || view instanceof Switch;
        if (interactive) {
            view.setMinimumWidth(Math.max(view.getMinimumWidth(), minimumTouchPx));
            view.setMinimumHeight(Math.max(view.getMinimumHeight(), minimumTouchPx));
            view.setFocusable(true);
        }
        if (view instanceof ImageView && interactive) {
            CharSequence description = view.getContentDescription();
            if (description == null || description.length() == 0) view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
        if (view instanceof Button && (view.getContentDescription()==null || view.getContentDescription().length()==0)) view.setContentDescription(((Button)view).getText());
        if (view instanceof Switch && android.os.Build.VERSION.SDK_INT >= 30) view.setStateDescription(((Switch)view).isChecked() ? "Включено" : "Выключено");
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) walk(group.getChildAt(i), minimumTouchPx);
        }
    }
}
