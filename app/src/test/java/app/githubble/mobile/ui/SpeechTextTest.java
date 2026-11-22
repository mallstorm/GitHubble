package app.githubble.mobile.ui;

import static org.junit.Assert.*;

import java.util.List;
import java.util.Locale;
import org.junit.Test;

public class SpeechTextTest {
    @Test public void codeLinksAndMarkupAreNotReadAloud() {
        String spoken = SpeechText.speakable("## План\n- **Сделаем** [сборку](https://ci.example/run/1)\n```bash\nrm -rf /\n```\nСм. https://example.com и `код`.");
        assertTrue(spoken.contains("План"));
        assertTrue(spoken.contains("Сделаем сборку"));
        assertTrue(spoken.contains("код"));
        assertFalse(spoken.contains("rm -rf"));
        assertFalse(spoken.contains("http"));
        assertFalse(spoken.contains("**"));
        assertFalse(spoken.contains("#"));
    }

    @Test public void actionLinksAreReadAsTheirLabel() {
        assertEquals("Продолжить", SpeechText.speakable("[Продолжить](githubble-action://continue)"));
    }

    @Test public void chunksRespectTheLimitAndKeepEverything() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 200; i++) text.append("Предложение номер ").append(i).append(". ");
        List<String> parts = SpeechText.chunks(text.toString(), 500);
        assertTrue(parts.size() > 3);
        StringBuilder joined = new StringBuilder();
        for (String part : parts) { assertTrue(part.length() <= 500); joined.append(part).append(' '); }
        assertTrue(joined.toString().contains("номер 199"));
        assertTrue(joined.toString().contains("номер 0."));
    }

    @Test public void veryLongSentenceIsStillSplit() {
        List<String> parts = SpeechText.chunks("слово ".repeat(400).trim(), 300);
        assertTrue(parts.size() >= 8);
        for (String part : parts) assertTrue(part.length() <= 300);
    }

    @Test public void voiceFollowsTheLanguageOfTheText() {
        assertEquals(Locale.KOREAN, SpeechText.localeOf("ko"));
        assertEquals(Locale.ENGLISH, SpeechText.localeOf("en"));
        assertEquals("ru", SpeechText.localeOf("und").getLanguage());
    }
}
