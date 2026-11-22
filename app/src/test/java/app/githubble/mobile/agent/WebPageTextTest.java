package app.githubble.mobile.agent;

import static org.junit.Assert.*;

import org.junit.Test;

public class WebPageTextTest {
    @Test public void onlyPublicHttpsPagesPass() {
        assertEquals("https://example.com/a", WebPageText.checkUrl("https://example.com/a#frag"));
        for (String bad : new String[] {"http://example.com", "https://127.0.0.1/", "https://192.168.0.1/x", "https://localhost/", "https://intranet/",
                "https://a.com:8443/", "https://user:pass@a.com/", "ftp://a.com", "javascript:alert(1)", "https://printer.local/", "", "https://[::1]/"}) {
            try { WebPageText.checkUrl(bad); fail(bad); } catch (IllegalArgumentException expected) { /* refused */ }
        }
    }

    @Test public void htmlBecomesReadableTextWithoutScriptsAndChrome() {
        String html = "<html><head><title>Hi &amp; bye</title><script>var secret=1;</script><style>p{}</style></head><body><nav>menu menu</nav>"
                + "<h1>Заголовок</h1><p>Первый <b>абзац</b> &mdash; текст &#1090;&#x44d;.</p><ul><li>один</li><li>два</li></ul><footer>футер</footer></body></html>";
        String text = WebPageText.extractText(html, 1000);
        assertEquals("Hi & bye", WebPageText.title(html));
        assertTrue(text.contains("# Заголовок"));
        assertTrue(text.contains("Первый абзац — текст тэ."));
        assertTrue(text.contains("- один\n- два"));
        assertFalse(text.contains("secret"));
        assertFalse(text.contains("menu"));
        assertFalse(text.contains("футер"));
        assertFalse(text.contains("Hi & bye"));
    }

    @Test public void articleIsPreferredOverSurroundingPage() {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 30; i++) body.append("Предложение номер ").append(i).append(" статьи. ");
        String html = "<body><div>Реклама рядом</div><article><p>" + body + "</p></article><div>Ещё реклама</div></body>";
        String text = WebPageText.extractText(html, 5000);
        assertTrue(text.contains("Предложение номер 29"));
        assertFalse(text.contains("Реклама"));
    }

    @Test public void limitAndThinPages() {
        assertEquals(10, WebPageText.extractText("<p>" + "x".repeat(100) + "</p>", 10).length());
        assertTrue(WebPageText.thin("<div id=app></div>"));
        assertFalse(WebPageText.thin("слово ".repeat(80)));
    }
}
