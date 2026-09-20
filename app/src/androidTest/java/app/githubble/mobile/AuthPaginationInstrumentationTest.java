package app.githubble.mobile;

import android.app.Instrumentation;
import android.os.Bundle;
import app.githubble.mobile.util.DeviceCodeFormatter;
import app.githubble.mobile.util.PaginationState;

/** Dependency-free on-device regression runner for authentication and pagination. */
public final class AuthPaginationInstrumentationTest extends Instrumentation {
    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override public void onStart() {
        super.onStart();
        Bundle result = new Bundle();
        try {
            testDeviceCodeNormalization();
            testRestPaginationPreventsDuplicateLoads();
            testGraphQlCursorProgression();
            result.putString("result", "PASS: auth + pagination instrumentation checks");
            finish(0, result);
        } catch (Throwable error) {
            result.putString("result", "FAIL: " + error.getMessage());
            finish(-1, result);
        }
    }

    private static void testDeviceCodeNormalization() {
        eq("85059224", DeviceCodeFormatter.normalize("8505-9224"));
        eq("AB12CD34", DeviceCodeFormatter.normalize("ab12 cd34"));
        eq("", DeviceCodeFormatter.normalize(null));
    }

    private static void testRestPaginationPreventsDuplicateLoads() {
        PaginationState state = new PaginationState();
        yes(state.begin()); no(state.begin());
        state.completeRest(50, 50);
        eq(2, state.page()); yes(state.hasMore()); yes(state.begin());
        state.completeRest(4, 50);
        no(state.hasMore()); no(state.begin());
    }

    private static void testGraphQlCursorProgression() {
        PaginationState state = new PaginationState();
        yes(state.begin()); state.completeGraphQl("cursor-1", true);
        eq("cursor-1", state.cursor()); yes(state.hasMore()); yes(state.begin());
        state.completeGraphQl("cursor-2", false); no(state.hasMore());
    }

    private static void yes(boolean value){if(!value)throw new AssertionError("Expected true");}
    private static void no(boolean value){if(value)throw new AssertionError("Expected false");}
    private static void eq(Object expected,Object actual){if(expected==null?actual!=null:!expected.equals(actual))throw new AssertionError("Expected "+expected+", got "+actual);}
}
