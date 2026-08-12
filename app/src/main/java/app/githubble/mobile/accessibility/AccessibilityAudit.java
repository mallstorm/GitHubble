package app.githubble.mobile.accessibility;

import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.Switch;
import java.util.ArrayList;
import java.util.List;

/** Runtime guardrails for touch targets, focus order and missing content descriptions. */
public final class AccessibilityAudit {
    private AccessibilityAudit() { }

    public static void prepareTree(View root, int minimumTouchPx) {
        if (root == null) return;
        List<View> focusable = new ArrayList<>();
        walk(root, minimumTouchPx, focusable);
        for (int i = 0; i < focusable.size() - 1; i++) {
            focusable.get(i).setNextFocusForwardId(focusable.get(i + 1).getId());
        }
    }

    private static void walk(View view, int minimumTouchPx, List<View> focusable) {
        boolean interactive = view.isClickable() || view instanceof Button || view instanceof Switch;
        if (interactive) {
            if (view.getId() == View.NO_ID) view.setId(View.generateViewId());
            view.setMinimumWidth(Math.max(view.getMinimumWidth(), minimumTouchPx));
            view.setMinimumHeight(Math.max(view.getMinimumHeight(), minimumTouchPx));
            view.setFocusable(true);
            focusable.add(view);
        }
        if (view instanceof ImageView && interactive) {
            CharSequence description = view.getContentDescription();
            if (description == null || description.length() == 0) view.setContentDescription("Действие");
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) walk(group.getChildAt(i), minimumTouchPx, focusable);
        }
    }
}
