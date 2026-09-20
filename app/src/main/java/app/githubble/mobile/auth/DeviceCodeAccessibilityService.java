package app.githubble.mobile.auth;

import android.accessibilityservice.AccessibilityService;
import android.os.Bundle;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Experimental, opt-in helper for the OFFICIAL GitHub Device Flow page in
 * Chrome Custom Tabs. Never receives access tokens or passwords.
 *
 * Safety gates: explicitly armed by the user for 45 seconds, Chrome package
 * only, recognizable device-code page, exactly eight visible, editable,
 * non-password one-character code inputs. No confirmation button is clicked.
 * If GitHub changes its DOM or Chrome hides fields from accessibility, fail
 * closed and leave manual paste / the visible code strip available.
 */
public final class DeviceCodeAccessibilityService extends AccessibilityService {
    private static final int MAX_NODES = 450;
    private boolean working;

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (working || event == null || !DeviceCodeAssist.isArmed(this)) return;
        CharSequence pkg = event.getPackageName();
        if (pkg == null || !isChrome(pkg.toString())) return;
        int kind = event.getEventType();
        if (kind != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && kind != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || root.getPackageName() == null
                || !isChrome(root.getPackageName().toString())) return;

        List<AccessibilityNodeInfo> inputs = new ArrayList<>();
        boolean[] devicePage = {false};
        int[] remaining = {MAX_NODES};
        inspect(root, inputs, devicePage, remaining, 0);
        // Eight fields plus a GitHub Device Flow-specific heading are required.
        if (!devicePage[0] || inputs.size() != 8) return;
        String code = DeviceCodeAssist.currentCode(this);
        if (!code.matches("[A-Z0-9]{8}")) return;

        working = true;
        try {
            boolean completed = true;
            for (int i = 0; i < 8; i++) {
                AccessibilityNodeInfo input = inputs.get(i);
                if (!input.refresh() || !input.isVisibleToUser()
                        || !input.isEditable() || input.isPassword()) {
                    completed = false;
                    break;
                }
                Bundle arguments = new Bundle();
                arguments.putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    code.substring(i, i + 1));
                if (!input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) {
                    completed = false;
                    break;
                }
            }
            if (completed) {
                DeviceCodeAssist.disarm(this);
                Toast.makeText(this,
                    "Код передан в восемь полей. Проверьте его и подтвердите вход на GitHub.",
                    Toast.LENGTH_LONG).show();
            }
        } finally {
            working = false;
        }
    }

    private static boolean isChrome(String name) {
        return "com.android.chrome".equals(name) || "com.chrome.beta".equals(name)
            || "com.chrome.dev".equals(name) || "com.chrome.canary".equals(name);
    }

    private static void inspect(AccessibilityNodeInfo node,
                                List<AccessibilityNodeInfo> inputs,
                                boolean[] devicePage, int[] remaining, int depth) {
        if (node == null || depth > 24 || --remaining[0] < 0) return;
        if (node.isVisibleToUser() && !node.isPassword()) {
            if (!node.isEditable()) {
                devicePage[0] |= looksLikeDevicePage(node.getText())
                    || looksLikeDevicePage(node.getContentDescription());
            } else if (node.isEnabled()) {
                CharSequence current = node.getText();
                if (current == null || current.length() <= 1) inputs.add(node);
            }
        }
        for (int i = 0; i < node.getChildCount() && remaining[0] > 0; i++) {
            inspect(node.getChild(i), inputs, devicePage, remaining, depth + 1);
        }
    }

    private static boolean looksLikeDevicePage(CharSequence text) {
        if (text == null || text.length() > 160) return false;
        String value = text.toString().toLowerCase(Locale.ROOT);
        return value.contains("device code") || value.contains("device verification")
            || value.contains("device activation") || value.contains("enter your code")
            || value.contains("код устройства") || value.contains("код авторизации github");
    }

    @Override public void onInterrupt() {
        DeviceCodeAssist.disarm(this);
    }
}
