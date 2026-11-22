package app.githubble.mobile.translation;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public class TranslationRouteTest {
    @Test public void subscriptionsComeFirstThenFreeKeysThenSelected() {
        List<String> order = TranslationRoute.order("auto", Arrays.asList("gemini", "grok-subscription", "chatgpt-plan", "zzz-other"), "zzz-other");
        assertEquals(Arrays.asList("chatgpt-plan", "grok-subscription", "gemini", "zzz-other"), order);
    }

    @Test public void explicitChoiceUsesOnlyThatService() {
        assertEquals(Collections.singletonList("grok-subscription"), TranslationRoute.order("grok-subscription", Arrays.asList("chatgpt-plan", "grok-subscription"), ""));
        assertTrue(TranslationRoute.order("claude-subscription", Arrays.asList("chatgpt-plan"), "").isEmpty());
    }

    @Test public void nothingConnectedMeansNothingToTryAndNoCustomEndpointsInAuto() {
        assertTrue(TranslationRoute.order("auto", Collections.<String>emptyList(), "").isEmpty());
        assertFalse(TranslationRoute.order("auto", Arrays.asList("custom-1", "proxy-external"), "").contains("custom-1"));
    }

    @Test public void fastTextModelIsPreferredAndNonTextModelsSkipped() {
        assertEquals("gpt-5-mini", TranslationRoute.pickModel("gpt-5", Arrays.asList("gpt-5", "gpt-image-1-mini", "gpt-5-mini")));
        assertEquals("gpt-5", TranslationRoute.pickModel("gpt-5", Arrays.asList("gpt-5", "gpt-4o-audio-preview")));
        assertEquals("grok-4", TranslationRoute.pickModel("", Arrays.asList("grok-4")));
        assertEquals("", TranslationRoute.pickModel("", Collections.<String>emptyList()));
    }
}
