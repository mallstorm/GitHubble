package app.githubble.mobile.ui;

import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.GridLayout;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import app.githubble.mobile.R;

/**
 * Layout safety net: keeps neighbouring buttons, chips and tiles from touching and removes the
 * default button shadow. Idempotent; each view is adjusted at most once.
 */
public final class UiPolish {
  private UiPolish() {}

  public static void install(View root, int minGapPx, int gapPx, int accent) {
    root.getViewTreeObserver().addOnGlobalLayoutListener(() -> apply(root, minGapPx, gapPx, accent));
  }

  public static void apply(View view, int minGapPx, int gapPx, int accent) {
    if (view instanceof Button) ((Button) view).setStateListAnimator(null);
    if (view instanceof Switch && view.getTag(R.id.ui_polished) == null) {
      Switch toggle = (Switch) view;
      toggle.setTag(R.id.ui_polished, Boolean.TRUE);
      int[][] states = {{android.R.attr.state_checked}, {}};
      toggle.setThumbTintList(new ColorStateList(states, new int[] {accent, Color.rgb(236, 238, 240)}));
      toggle.setTrackTintList(new ColorStateList(states, new int[] {Color.argb(120, Color.red(accent), Color.green(accent), Color.blue(accent)), Color.argb(70, 120, 128, 135)}));
    }
    if (!(view instanceof ViewGroup)) return;
    ViewGroup group = (ViewGroup) view;
    if (group instanceof LinearLayout) separate((LinearLayout) group, minGapPx, gapPx);
    else if (group instanceof GridLayout) spaceGrid((GridLayout) group, gapPx / 2);
    for (int i = 0; i < group.getChildCount(); i++) apply(group.getChildAt(i), minGapPx, gapPx, accent);
  }

  private static void separate(LinearLayout row, int minGapPx, int gapPx) {
    boolean horizontal = row.getOrientation() == LinearLayout.HORIZONTAL;
    View previous = null;
    for (int i = 0; i < row.getChildCount(); i++) {
      View child = row.getChildAt(i);
      if (child.getVisibility() == View.GONE) continue;
      if (previous != null && surface(previous, horizontal) && surface(child, horizontal)
          && child.getTag(R.id.ui_polished) == null
          && child.getLayoutParams() instanceof LinearLayout.LayoutParams) {
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) child.getLayoutParams();
        LinearLayout.LayoutParams before = (LinearLayout.LayoutParams) previous.getLayoutParams();
        int gap = horizontal ? before.rightMargin + params.leftMargin : before.bottomMargin + params.topMargin;
        child.setTag(R.id.ui_polished, Boolean.TRUE);
        if (gap < minGapPx) {
          if (horizontal) params.leftMargin += gapPx - gap; else params.topMargin += gapPx - gap;
          child.setLayoutParams(params);
        }
      }
      previous = child;
    }
  }

  private static void spaceGrid(GridLayout grid, int halfGapPx) {
    for (int i = 0; i < grid.getChildCount(); i++) {
      View child = grid.getChildAt(i);
      if (child.getTag(R.id.ui_polished) != null || !surface(child, true)
          && !(child.getBackground() != null && child.isClickable())) continue;
      if (!(child.getLayoutParams() instanceof GridLayout.LayoutParams)) continue;
      GridLayout.LayoutParams params = (GridLayout.LayoutParams) child.getLayoutParams();
      child.setTag(R.id.ui_polished, Boolean.TRUE);
      int l = Math.max(params.leftMargin, halfGapPx), t = Math.max(params.topMargin, halfGapPx);
      int r = Math.max(params.rightMargin, halfGapPx), b = Math.max(params.bottomMargin, halfGapPx);
      if (l != params.leftMargin || t != params.topMargin || r != params.rightMargin || b != params.bottomMargin) {
        params.setMargins(l, t, r, b);
        child.setLayoutParams(params);
      }
    }
  }

  private static boolean surface(View view, boolean horizontal) {
    if (!(view.getLayoutParams() instanceof LinearLayout.LayoutParams) || view.getBackground() == null) return false;
    return view instanceof Button || view.isClickable() && (horizontal || view instanceof TextView);
  }
}
